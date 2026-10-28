/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.neo4j.kernel.impl.constraints;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.collections.api.IntIterable;
import org.eclipse.collections.api.set.primitive.LongSet;
import org.neo4j.exceptions.KernelException;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.schema.EndpointType;
import org.neo4j.internal.schema.constraints.PropertyTypeSet;
import org.neo4j.internal.schema.constraints.TypeRepresentation;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.RelationshipSelection;
import org.neo4j.storageengine.api.StorageProperty;
import org.neo4j.storageengine.api.txstate.RelationshipModifications;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;
import org.neo4j.values.storable.Value;

/**
 * A unified transaction state visitor that enforces all constraint rules
 * (vertex attributes, edge attributes, label coexistence, endpoint bindings)
 * during transaction processing.
 *
 * <p>This class merges the responsibilities of node mutation enforcement,
 * relationship mutation enforcement, and visitor delegation into a single
 * pipeline that extends {@link TxStateVisitor.Delegator}.</p>
 *
 * <p>Validation logic is implemented as private static methods (pure functions)
 * where possible, with all context passed as parameters.</p>
 */
final class GraphMutationPipeline extends TxStateVisitor.Delegator {

    private final IntegrityRuleRegistry ruleRegistry;
    private final ValidationFaultEmitter faultEmitter;
    private final Read read;

    // --- Vertex (Node) Cursors ---
    private final NodeCursor nodeCursor;
    private final PropertyCursor propertyCursor;

    // --- Shared Cursor Infrastructure ---
    private final CursorFactory cursorFactory;
    private final CursorContext cursorContext;
    private final MemoryTracker memoryTracker;

    // --- Lazy-Allocated Cursors ---
    private RelationshipTraversalCursor traversalCursor;
    private NodeCursor lazyNodeCursor;

    // --- Reusable Scratch Sets ---
    private final Set<Integer> discoveredPropertyKeys = new HashSet<>();
    private final Set<Integer> presentPropertyKeys = new HashSet<>();

    // --- Double-Close Guard ---
    private boolean closed;

    /**
     * Creates a new graph mutation pipeline that decorates the given delegate visitor
     * with constraint enforcement logic.
     *
     * @param delegate the underlying transaction state visitor to delegate to
     * @param ruleRegistry the compiled integrity rules to enforce
     * @param faultEmitter the emitter for reporting constraint violations
     * @param read the kernel read operations for cursor positioning
     * @param nodeCursor a pre-allocated node cursor for vertex operations
     * @param propertyCursor a pre-allocated property cursor for attribute inspection
     * @param cursorFactory the factory for allocating additional cursors on demand
     * @param cursorContext the cursor context for page cache access
     * @param memoryTracker the memory tracker for cursor allocations
     */
    GraphMutationPipeline(
            final TxStateVisitor delegate,
            final IntegrityRuleRegistry ruleRegistry,
            final ValidationFaultEmitter faultEmitter,
            final Read read,
            final NodeCursor nodeCursor,
            final PropertyCursor propertyCursor,
            final CursorFactory cursorFactory,
            final CursorContext cursorContext,
            final MemoryTracker memoryTracker) {
        super(delegate);
        this.ruleRegistry = ruleRegistry;
        this.faultEmitter = faultEmitter;
        this.read = read;
        this.nodeCursor = nodeCursor;
        this.propertyCursor = propertyCursor;
        this.cursorFactory = cursorFactory;
        this.cursorContext = cursorContext;
        this.memoryTracker = memoryTracker;
        this.closed = false;
    }

    // ========================================================================
    // --- Vertex Property Changes ---
    // ========================================================================

    /**
     * Intercepts node property changes to enforce attribute presence and type
     * constraints before delegating to the underlying visitor.
     *
     * @param id the node entity id
     * @param added properties being added
     * @param changed properties being changed
     * @param removed property key ids being removed
     * @throws ConstraintValidationException if a constraint is violated
     */
    @Override
    public void visitNodePropertyChanges(
            final long id,
            final Iterable<StorageProperty> added,
            final Iterable<StorageProperty> changed,
            final IntIterable removed)
            throws ConstraintValidationException {
        // --- Step 1: Position cursor on the node ---
        read.singleNode(id, nodeCursor);
        if (!nodeCursor.next()) {
            throw new IllegalStateException(
                    "Node " + id + " should exist during property change validation but was not found");
        }

        final var nodeLabels = nodeCursor.labels();
        if (nodeLabels.numberOfTokens() == 0) {
            super.visitNodePropertyChanges(id, added, changed, removed);
            return;
        }

        // --- Step 2: Enforce attribute presence on removal ---
        enforceVertexPresenceOnRemoval(id, removed, nodeLabels);

        // --- Step 3: Enforce property type rules on add/change ---
        enforceVertexTypeRules(id, added, changed, nodeLabels);

        super.visitNodePropertyChanges(id, added, changed, removed);
    }

    /**
     * Checks that removing properties does not violate any existence constraints
     * on the node's labels.
     *
     * @param nodeId the node entity id
     * @param removed the property key ids being removed
     * @param labels the node's current label set
     * @throws ConstraintValidationException if a required property is being removed
     */
    private void enforceVertexPresenceOnRemoval(
            final long nodeId, final IntIterable removed, final org.neo4j.internal.kernel.api.TokenSet labels)
            throws ConstraintValidationException {
        if (removed.isEmpty()) {
            return;
        }

        // --- Convert Eclipse IntIterable to java.util Set at boundary ---
        final var removedSet = new HashSet<Integer>();
        removed.forEach(removedSet::add);

        for (final int label : labels.all()) {
            final Set<Integer> required = ruleRegistry.requiredVertexPropertyTokens(label);
            if (required == null) {
                continue;
            }
            for (final int reqProp : required) {
                if (removedSet.contains(reqProp)) {
                    faultEmitter.emitVertexAttributeAbsence(label, nodeId, reqProp);
                }
            }
        }
    }

    /**
     * Checks that added or changed property values conform to type constraints.
     *
     * @param nodeId the node entity id
     * @param added properties being added
     * @param changed properties being changed
     * @param labels the node's current label set
     * @throws ConstraintValidationException if a property type rule is violated
     */
    private void enforceVertexTypeRules(
            final long nodeId,
            final Iterable<StorageProperty> added,
            final Iterable<StorageProperty> changed,
            final org.neo4j.internal.kernel.api.TokenSet labels)
            throws ConstraintValidationException {
        for (final int label : labels.all()) {
            final var typeMap = ruleRegistry.vertexTypeConstraints(label);
            if (typeMap == null) {
                continue;
            }

            checkVertexPropertyTypes(typeMap, added, label, nodeId);
            checkVertexPropertyTypes(typeMap, changed, label, nodeId);
        }
    }

    /**
     * Validates a set of properties against the type constraints for a specific label.
     *
     * @param typeMap the type constraint map for the label
     * @param properties the properties to check
     * @param label the label token id
     * @param nodeId the node entity id
     * @throws ConstraintValidationException if a type rule is violated
     */
    private void checkVertexPropertyTypes(
            final Map<Integer, PropertyTypeSet> typeMap,
            final Iterable<StorageProperty> properties,
            final int label,
            final long nodeId)
            throws ConstraintValidationException {
        for (final var prop : properties) {
            final var allowed = typeMap.get(prop.propertyKeyId());
            if (allowed != null && TypeRepresentation.disallows(allowed, prop.value())) {
                faultEmitter.emitVertexTypeFault(label, nodeId, prop.propertyKeyId(), prop.value());
            }
        }
    }

    // ========================================================================
    // --- Vertex Label Changes ---
    // ========================================================================

    /**
     * Intercepts node label changes to enforce type rules, existence rules,
     * label coexistence, and endpoint topology constraints before delegating.
     *
     * @param id the node entity id
     * @param added label ids being added
     * @param removed label ids being removed
     * @throws ConstraintValidationException if a constraint is violated
     */
    @Override
    public void visitNodeLabelChanges(final long id, final LongSet added, final LongSet removed)
            throws ConstraintValidationException {
        read.singleNode(id, nodeCursor);
        if (!nodeCursor.next()) {
            throw new IllegalStateException(
                    "Node " + id + " should exist during label change validation but was not found");
        }

        // --- Step 1: Discover existing property keys ---
        discoveredPropertyKeys.clear();
        nodeCursor.properties(propertyCursor);
        while (propertyCursor.next()) {
            discoveredPropertyKeys.add(propertyCursor.propertyKey());
        }

        // --- Step 2: Enforce type rules for newly added labels ---
        enforceTypeRulesForNewLabels(id, added);

        // --- Step 3: Enforce existence constraints for newly added labels ---
        enforceExistenceForNewLabels(id, added);

        // --- Step 4: Enforce label coexistence rules ---
        enforceLabelCoexistence(id, added, removed);

        // --- Step 5: Enforce endpoint topology on label removal ---
        if (!removed.isEmpty() && ruleRegistry.hasEndpointBindings()) {
            enforceEndpointTopologyOnLabelDrop(removed);
        }

        super.visitNodeLabelChanges(id, added, removed);
    }

    /**
     * Checks that existing property values on the node conform to type constraints
     * imposed by the newly added labels.
     *
     * @param nodeId the node entity id
     * @param addedLabels the label ids being added
     * @throws ConstraintValidationException if a type rule is violated
     */
    private void enforceTypeRulesForNewLabels(final long nodeId, final LongSet addedLabels)
            throws ConstraintValidationException {
        nodeCursor.properties(propertyCursor);
        while (propertyCursor.next()) {
            final int propKey = propertyCursor.propertyKey();
            Value val = null;
            for (final var labelIter = addedLabels.longIterator(); labelIter.hasNext(); ) {
                final int label = (int) labelIter.next();
                final var typeMap = ruleRegistry.vertexTypeConstraints(label);
                if (typeMap == null) {
                    continue;
                }
                final var allowed = typeMap.get(propKey);
                if (allowed != null) {
                    if (val == null) {
                        val = propertyCursor.propertyValue();
                    }
                    if (TypeRepresentation.disallows(allowed, val)) {
                        faultEmitter.emitVertexTypeFault(label, nodeId, propKey, val);
                    }
                }
            }
        }
    }

    /**
     * Checks that all mandatory properties exist on the node for each newly added label.
     *
     * @param nodeId the node entity id
     * @param addedLabels the label ids being added
     * @throws ConstraintValidationException if a required property is missing
     */
    private void enforceExistenceForNewLabels(final long nodeId, final LongSet addedLabels)
            throws ConstraintValidationException {
        final var labelIter = addedLabels.longIterator();
        while (labelIter.hasNext()) {
            final int label = (int) labelIter.next();
            final Set<Integer> required = ruleRegistry.requiredVertexPropertyTokens(label);
            if (required == null) {
                continue;
            }
            for (final int reqProp : required) {
                if (!discoveredPropertyKeys.contains(reqProp)) {
                    faultEmitter.emitVertexAttributeAbsence(label, nodeId, reqProp);
                }
            }
        }
    }

    /**
     * Enforces label coexistence rules: when adding a label, all prerequisite labels
     * must be present; when removing a label, no dependent labels may remain.
     *
     * @param nodeId the node entity id
     * @param addedLabels the label ids being added
     * @param removedLabels the label ids being removed
     * @throws ConstraintValidationException if a coexistence rule is violated
     */
    private void enforceLabelCoexistence(final long nodeId, final LongSet addedLabels, final LongSet removedLabels)
            throws ConstraintValidationException {
        if (!ruleRegistry.hasCoexistenceRules()) {
            return;
        }

        // --- Check prerequisites for added labels ---
        final var addedIter = addedLabels.longIterator();
        while (addedIter.hasNext()) {
            final int addedLabel = (int) addedIter.next();
            final Set<Integer> prerequisites = ruleRegistry.coexistentLabels(addedLabel);
            if (prerequisites == null) {
                continue;
            }
            for (final int prereqLabel : prerequisites) {
                if (!nodeCursor.hasLabel(prereqLabel)) {
                    faultEmitter.emitLabelCorequisiteDeficit(prereqLabel, nodeId, addedLabel);
                }
            }
        }

        // --- Check dependents for removed labels ---
        final var removedIter = removedLabels.longIterator();
        while (removedIter.hasNext()) {
            final int removedLabel = (int) removedIter.next();
            final Set<Integer> dependents = ruleRegistry.inverseDependencies(removedLabel);
            if (dependents == null) {
                continue;
            }
            for (final int dependentLabel : dependents) {
                if (nodeCursor.hasLabel(dependentLabel)) {
                    faultEmitter.emitLabelCorequisiteDeficit(removedLabel, nodeCursor.nodeReference(), dependentLabel);
                }
            }
        }
    }

    /**
     * When a label is removed from a node, checks that no relationship endpoint
     * topology constraints are violated for relationships connected to this node.
     *
     * @param removedLabels the label ids being removed
     * @throws ConstraintValidationException if an endpoint topology rule is violated
     */
    private void enforceEndpointTopologyOnLabelDrop(final LongSet removedLabels) throws ConstraintValidationException {
        final Set<Integer> constrainedTypes = ruleRegistry.topologyBoundEdgeTypes();
        if (constrainedTypes.isEmpty()) {
            return;
        }

        if (traversalCursor == null) {
            traversalCursor = cursorFactory.allocateFullAccessRelationshipTraversalCursor(cursorContext, memoryTracker);
        }

        final var srcLabels = ruleRegistry.originLabelBindings();
        final var tgtLabels = ruleRegistry.terminusLabelBindings();
        final long nodeRef = nodeCursor.reference();

        nodeCursor.relationships(traversalCursor, RelationshipSelection.ALL_RELATIONSHIPS);

        while (traversalCursor.next()) {
            final int relType = traversalCursor.type();

            if (!constrainedTypes.contains(relType)) {
                continue;
            }

            // --- Check source-side endpoint constraints ---
            if (nodeRef == traversalCursor.sourceNodeReference() && srcLabels.containsKey(relType)) {
                final int requiredLabel = srcLabels.get(relType);
                if (removedLabels.contains(requiredLabel)) {
                    faultEmitter.emitEndpointLabelDeficit(
                            EndpointType.START, requiredLabel, nodeRef, traversalCursor.reference(), relType);
                }
            }

            // --- Check target-side endpoint constraints ---
            if (nodeRef == traversalCursor.targetNodeReference() && tgtLabels.containsKey(relType)) {
                final int requiredLabel = tgtLabels.get(relType);
                if (removedLabels.contains(requiredLabel)) {
                    faultEmitter.emitEndpointLabelDeficit(
                            EndpointType.END, requiredLabel, nodeRef, traversalCursor.reference(), relType);
                }
            }
        }
    }

    // ========================================================================
    // --- Edge (Relationship) Modifications ---
    // ========================================================================

    /**
     * Intercepts relationship modifications to enforce endpoint label,
     * property type, and property existence constraints before delegating.
     *
     * @param modifications the relationship creation and update operations
     * @throws ConstraintValidationException if a constraint is violated
     */
    @Override
    public void visitRelationshipModifications(final RelationshipModifications modifications)
            throws ConstraintValidationException {
        modifications.creations().forEach(this::enforceEdgeCreation);
        modifications.updates().forEach(this::enforceEdgeUpdate);
        super.visitRelationshipModifications(modifications);
    }

    /**
     * Enforces all constraints for a newly created relationship: endpoint labels,
     * property types, and property existence.
     *
     * @param id the relationship entity id
     * @param type the relationship type token id
     * @param src the source node entity id
     * @param tgt the target node entity id
     * @param added properties being added
     * @param changed properties being changed
     * @param removed property key ids being removed
     * @throws ConstraintValidationException if a constraint is violated
     */
    private void enforceEdgeCreation(
            final long id,
            final int type,
            final long src,
            final long tgt,
            final Iterable<StorageProperty> added,
            final Iterable<StorageProperty> changed,
            final IntIterable removed)
            throws ConstraintValidationException {

        // --- Step 1: Verify endpoint label constraints ---
        verifyEndpointLabels(id, type, src, tgt);

        // --- Step 2: Check type rules and collect present property keys ---
        presentPropertyKeys.clear();
        final var typeMap = ruleRegistry.edgeTypeConstraints(type);
        for (final var prop : added) {
            presentPropertyKeys.add(prop.propertyKeyId());
            if (typeMap != null) {
                final var allowed = typeMap.get(prop.propertyKeyId());
                if (allowed != null && TypeRepresentation.disallows(allowed, prop.value())) {
                    faultEmitter.emitEdgeTypeFault(prop.propertyKeyId(), id, type, prop.value());
                }
            }
        }

        // --- Step 3: Verify all required properties are present ---
        verifyEdgePresence(id, type, presentPropertyKeys);
    }

    /**
     * Enforces constraints for an updated relationship: property type rules
     * and property removal constraints.
     *
     * @param id the relationship entity id
     * @param type the relationship type token id
     * @param src the source node entity id
     * @param tgt the target node entity id
     * @param added properties being added
     * @param changed properties being changed
     * @param removed property key ids being removed
     * @throws ConstraintValidationException if a constraint is violated
     */
    private void enforceEdgeUpdate(
            final long id,
            final int type,
            final long src,
            final long tgt,
            final Iterable<StorageProperty> added,
            final Iterable<StorageProperty> changed,
            final IntIterable removed)
            throws ConstraintValidationException {
        final var typeMap = ruleRegistry.edgeTypeConstraints(type);
        if (typeMap != null) {
            checkEdgePropertyTypes(typeMap, added, id, type);
            checkEdgePropertyTypes(typeMap, changed, id, type);
        }

        verifyEdgePropertyRemoval(id, type, removed);
    }

    /**
     * Validates a set of properties against the type constraints for a relationship type.
     *
     * @param typeMap the type constraint map for the relationship type
     * @param properties the properties to check
     * @param relId the relationship entity id
     * @param relType the relationship type token id
     * @throws ConstraintValidationException if a type rule is violated
     */
    private void checkEdgePropertyTypes(
            final Map<Integer, PropertyTypeSet> typeMap,
            final Iterable<StorageProperty> properties,
            final long relId,
            final int relType)
            throws ConstraintValidationException {
        for (final var prop : properties) {
            final var allowed = typeMap.get(prop.propertyKeyId());
            if (allowed != null && TypeRepresentation.disallows(allowed, prop.value())) {
                faultEmitter.emitEdgeTypeFault(prop.propertyKeyId(), relId, relType, prop.value());
            }
        }
    }

    /**
     * Verifies that all required properties are present on a newly created relationship.
     *
     * @param relId the relationship entity id
     * @param relType the relationship type token id
     * @param keys the set of property keys present on the relationship
     * @throws ConstraintValidationException if a required property is missing
     */
    private void verifyEdgePresence(final long relId, final int relType, final Set<Integer> keys)
            throws ConstraintValidationException {
        final var required = ruleRegistry.requiredEdgePropertyTokens(relType);
        if (required == null) {
            return;
        }
        for (final int reqProp : required) {
            if (!keys.contains(reqProp)) {
                faultEmitter.emitEdgeAttributeAbsence(relId, reqProp, relType);
            }
        }
    }

    /**
     * Verifies that removing properties from a relationship does not violate
     * existence constraints.
     *
     * @param relId the relationship entity id
     * @param relType the relationship type token id
     * @param removedPropertyIds the property key ids being removed
     * @throws ConstraintValidationException if a required property is being removed
     */
    private void verifyEdgePropertyRemoval(final long relId, final int relType, final IntIterable removedPropertyIds)
            throws ConstraintValidationException {
        final var required = ruleRegistry.requiredEdgePropertyTokens(relType);
        if (required == null) {
            return;
        }
        final var remIter = removedPropertyIds.intIterator();
        while (remIter.hasNext()) {
            final int removedProp = remIter.next();
            if (required.contains(removedProp)) {
                faultEmitter.emitEdgeAttributeAbsence(relId, removedProp, relType);
            }
        }
    }

    /**
     * Verifies that the source and target nodes of a relationship have the
     * labels required by endpoint binding constraints.
     *
     * @param relId the relationship entity id
     * @param relType the relationship type token id
     * @param srcNode the source node entity id
     * @param tgtNode the target node entity id
     * @throws ConstraintValidationException if an endpoint label is missing
     */
    private void verifyEndpointLabels(final long relId, final int relType, final long srcNode, final long tgtNode)
            throws ConstraintValidationException {
        if (!ruleRegistry.hasEdgeEndpointBindings()) {
            return;
        }

        if (lazyNodeCursor == null) {
            lazyNodeCursor = cursorFactory.allocateFullAccessNodeCursor(cursorContext, memoryTracker);
        }

        final var srcLabels = ruleRegistry.edgeOriginLabels();
        final var tgtLabels = ruleRegistry.edgeTerminusLabels();

        // --- Check source endpoint ---
        if (srcLabels.containsKey(relType)) {
            final int requiredLabel = srcLabels.get(relType);
            read.singleNode(srcNode, lazyNodeCursor);
            if (!lazyNodeCursor.next() || !lazyNodeCursor.hasLabel(requiredLabel)) {
                faultEmitter.emitEndpointLabelDeficit(EndpointType.START, requiredLabel, srcNode, relId, relType);
            }
        }

        // --- Check target endpoint ---
        if (tgtLabels.containsKey(relType)) {
            final int requiredLabel = tgtLabels.get(relType);
            read.singleNode(tgtNode, lazyNodeCursor);
            if (!lazyNodeCursor.next() || !lazyNodeCursor.hasLabel(requiredLabel)) {
                faultEmitter.emitEndpointLabelDeficit(EndpointType.END, requiredLabel, tgtNode, relId, relType);
            }
        }
    }

    // ========================================================================
    // --- Resource Cleanup ---
    // ========================================================================

    /**
     * Closes all allocated cursors and delegates to the parent close.
     * Guarded against double-close.
     *
     * @throws KernelException if the delegate close fails
     */
    @Override
    public void close() throws KernelException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            org.neo4j.io.IOUtils.closeAllUnchecked(nodeCursor, propertyCursor, traversalCursor, lazyNodeCursor);
        } finally {
            super.close();
        }
    }
}
