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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.constraints.PropertyTypeSet;
import org.neo4j.util.Preconditions;

/**
 * Compiles constraint descriptors from the schema into optimized lookup structures
 * for fast integrity rule evaluation during transaction processing.
 *
 * <p>This registry organizes constraints into three categories of rules:
 * attribute presence/type rules, label coexistence rules, and endpoint binding rules.
 * Each category is represented as a Java 17 record containing {@code java.util} maps.</p>
 */
final class IntegrityRuleRegistry {

    // --- Grouped Data Records ---

    /**
     * Holds vertex (node) and edge (relationship) attribute rules:
     * which properties are mandatory and which type constraints apply.
     *
     * @param vertexMandatoryAttributes required property keys per label
     * @param vertexTypeConstraints property type rules per label
     * @param edgeMandatoryAttributes required property keys per relationship type
     * @param edgeTypeConstraints property type rules per relationship type
     */
    record AttributeRules(
            Map<Integer, Set<Integer>> vertexMandatoryAttributes,
            Map<Integer, Map<Integer, PropertyTypeSet>> vertexTypeConstraints,
            Map<Integer, Set<Integer>> edgeMandatoryAttributes,
            Map<Integer, Map<Integer, PropertyTypeSet>> edgeTypeConstraints) {}

    /**
     * Holds label coexistence rules: which labels require other labels to be present.
     *
     * @param coexistentLabels maps a label to the set of labels it requires
     * @param inverseDependencies maps a required label to the set of labels that depend on it
     */
    record LabelCoexistenceRules(
            Map<Integer, Set<Integer>> coexistentLabels, Map<Integer, Set<Integer>> inverseDependencies) {}

    /**
     * Holds endpoint binding rules for relationship types: which labels are required
     * on the source (origin) and target (terminus) nodes.
     *
     * @param mandatedOriginLabels required label on the start node, per relationship type
     * @param mandatedTerminusLabels required label on the end node, per relationship type
     * @param edgeOriginLabels origin label bindings used during relationship creation
     * @param edgeTerminusLabels terminus label bindings used during relationship creation
     * @param topologyBoundEdgeTypes set of relationship types that have endpoint constraints
     */
    record EndpointBindingRules(
            Map<Integer, Integer> mandatedOriginLabels,
            Map<Integer, Integer> mandatedTerminusLabels,
            Map<Integer, Integer> edgeOriginLabels,
            Map<Integer, Integer> edgeTerminusLabels,
            Set<Integer> topologyBoundEdgeTypes) {}

    private final AttributeRules attributeRules;

    private final LabelCoexistenceRules labelCoexistenceRules;

    private final EndpointBindingRules endpointBindingRules;

    /**
     * Private constructor; use {@link #compileFromSchema(Iterator)} factory method.
     *
     * @param attributeRules the compiled attribute presence and type rules
     * @param labelCoexistenceRules the compiled label coexistence rules
     * @param endpointBindingRules the compiled endpoint binding rules
     */
    private IntegrityRuleRegistry(
            final AttributeRules attributeRules,
            final LabelCoexistenceRules labelCoexistenceRules,
            final EndpointBindingRules endpointBindingRules) {
        this.attributeRules = attributeRules;
        this.labelCoexistenceRules = labelCoexistenceRules;
        this.endpointBindingRules = endpointBindingRules;
    }

    // --- Factory Method ---

    /**
     * Compiles an iterator of constraint descriptors into an {@link IntegrityRuleRegistry}.
     * Returns {@link Optional#empty()} if no enforceable constraints are found.
     *
     * @param descriptors the constraint descriptors from the schema
     * @return an optional registry, empty if no enforceable constraints exist
     */
    static Optional<IntegrityRuleRegistry> compileFromSchema(final Iterator<ConstraintDescriptor> descriptors) {
        final var accumulator = new RuleAccumulatorImpl();

        while (descriptors.hasNext()) {
            final var desc = descriptors.next();

            switch (desc.type()) {
                case UNIQUE -> {
                    // Uniqueness constraints are handled by the index infrastructure
                }
                case EXISTS, UNIQUE_EXISTS -> accumulator.accumulateExistence(desc);
                case PROPERTY_TYPE -> accumulator.accumulateTypeRule(desc);
                case RELATIONSHIP_ENDPOINT_LABEL -> accumulator.accumulateEndpoint(desc);
                case NODE_LABEL_EXISTENCE -> accumulator.accumulateLabelCoexistence(desc);
            }
        }

        return accumulator.assemble();
    }

    // --- Sealed Accumulator Interface ---

    /**
     * Sealed interface for building an {@link IntegrityRuleRegistry} from individual
     * constraint descriptors. Only one permitted implementation exists.
     */
    sealed interface RuleAccumulator permits RuleAccumulatorImpl {

        /**
         * Accumulates an existence constraint descriptor.
         *
         * @param desc the constraint descriptor
         */
        void accumulateExistence(ConstraintDescriptor desc);

        /**
         * Accumulates a property type constraint descriptor.
         *
         * @param desc the constraint descriptor
         */
        void accumulateTypeRule(ConstraintDescriptor desc);

        /**
         * Accumulates a relationship endpoint label constraint descriptor.
         *
         * @param desc the constraint descriptor
         */
        void accumulateEndpoint(ConstraintDescriptor desc);

        /**
         * Accumulates a node label existence (coexistence) constraint descriptor.
         *
         * @param desc the constraint descriptor
         */
        void accumulateLabelCoexistence(ConstraintDescriptor desc);

        /**
         * Assembles the accumulated rules into an {@link IntegrityRuleRegistry}.
         *
         * @return an optional registry, empty if no rules were accumulated
         */
        Optional<IntegrityRuleRegistry> assemble();
    }

    /**
     * The sole permitted implementation of {@link RuleAccumulator}. Collects
     * constraint descriptors and builds the three rule groups.
     */
    private static final class RuleAccumulatorImpl implements RuleAccumulator {

        private final Map<Integer, Set<Integer>> vertexMandatoryAttributes = new HashMap<>();
        private final Map<Integer, Map<Integer, PropertyTypeSet>> vertexTypeConstraints = new HashMap<>();
        private final Map<Integer, Set<Integer>> edgeMandatoryAttributes = new HashMap<>();
        private final Map<Integer, Map<Integer, PropertyTypeSet>> edgeTypeConstraints = new HashMap<>();

        private final Map<Integer, Set<Integer>> coexistentLabels = new HashMap<>();
        private final Map<Integer, Set<Integer>> inverseDependencies = new HashMap<>();

        private final Map<Integer, Integer> mandatedOriginLabels = new HashMap<>();
        private final Map<Integer, Integer> mandatedTerminusLabels = new HashMap<>();
        private final Map<Integer, Integer> edgeOriginLabels = new HashMap<>();
        private final Map<Integer, Integer> edgeTerminusLabels = new HashMap<>();
        private final Set<Integer> topologyBoundEdgeTypes = new HashSet<>();

        private boolean hasAny = false;

        /**
         * Accumulates an existence or key constraint, recording the required property
         * keys for the appropriate entity type (vertex or edge).
         *
         * @param desc the existence or key constraint descriptor
         */
        @Override
        public void accumulateExistence(final ConstraintDescriptor desc) {
            hasAny = true;
            switch (desc.schema().entityType()) {
                case NODE -> {
                    final int labelId = desc.schema().getLabelId();
                    final var keys = vertexMandatoryAttributes.computeIfAbsent(labelId, k -> new HashSet<>());
                    for (final int propId : desc.schema().getPropertyIds()) {
                        keys.add(propId);
                    }
                }
                case RELATIONSHIP -> {
                    final int relTypeId = desc.schema().getRelTypeId();
                    final var keys = edgeMandatoryAttributes.computeIfAbsent(relTypeId, k -> new HashSet<>());
                    for (final int propId : desc.schema().getPropertyIds()) {
                        keys.add(propId);
                    }
                }
            }
        }

        /**
         * Accumulates a property type constraint, recording the allowed property
         * type set for each property key under the appropriate entity type.
         *
         * @param desc the property type constraint descriptor
         */
        @Override
        public void accumulateTypeRule(final ConstraintDescriptor desc) {
            hasAny = true;
            final var typeDesc = desc.asPropertyTypeConstraint();
            switch (desc.schema().entityType()) {
                case NODE -> {
                    final int labelId = typeDesc.schema().getLabelId();
                    final var labelMap = vertexTypeConstraints.computeIfAbsent(labelId, k -> new HashMap<>());
                    for (final int propKeyId : typeDesc.schema().getPropertyIds()) {
                        labelMap.put(propKeyId, typeDesc.propertyType());
                    }
                }
                case RELATIONSHIP -> {
                    final int relTypeId = typeDesc.schema().getRelTypeId();
                    final var typeMap = edgeTypeConstraints.computeIfAbsent(relTypeId, k -> new HashMap<>());
                    for (final int propKeyId : typeDesc.schema().getPropertyIds()) {
                        typeMap.put(propKeyId, typeDesc.propertyType());
                    }
                }
            }
        }

        /**
         * Accumulates a relationship endpoint label constraint, recording the required
         * label on the start or end node for a given relationship type.
         *
         * @param desc the endpoint label constraint descriptor
         * @throws IllegalStateException if a duplicate constraint is detected
         */
        @Override
        public void accumulateEndpoint(final ConstraintDescriptor desc) {
            hasAny = true;
            final var epDesc = desc.asRelationshipEndpointLabelConstraint();
            final int relTypeId = epDesc.schema().getRelTypeId();
            final int epLabelId = epDesc.endpointLabelId();

            topologyBoundEdgeTypes.add(relTypeId);

            switch (epDesc.endpointType()) {
                case START -> {
                    Preconditions.checkState(
                            !mandatedOriginLabels.containsKey(relTypeId),
                            "Duplicate start-node endpoint label constraint for relTypeId=%d:"
                                    + " existing labelId=%d, attempted labelId=%d",
                            relTypeId,
                            mandatedOriginLabels.getOrDefault(relTypeId, -1),
                            epLabelId);
                    mandatedOriginLabels.put(relTypeId, epLabelId);

                    Preconditions.checkState(
                            !edgeOriginLabels.containsKey(relTypeId),
                            "Duplicate start-node endpoint label constraint (rel-side) for relTypeId=%d",
                            relTypeId);
                    edgeOriginLabels.put(relTypeId, epLabelId);
                }
                case END -> {
                    Preconditions.checkState(
                            !mandatedTerminusLabels.containsKey(relTypeId),
                            "Duplicate end-node endpoint label constraint for relTypeId=%d:"
                                    + " existing labelId=%d, attempted labelId=%d",
                            relTypeId,
                            mandatedTerminusLabels.getOrDefault(relTypeId, -1),
                            epLabelId);
                    mandatedTerminusLabels.put(relTypeId, epLabelId);

                    Preconditions.checkState(
                            !edgeTerminusLabels.containsKey(relTypeId),
                            "Duplicate end-node endpoint label constraint (rel-side) for relTypeId=%d",
                            relTypeId);
                    edgeTerminusLabels.put(relTypeId, epLabelId);
                }
            }
        }

        /**
         * Accumulates a node label existence (coexistence) constraint, recording
         * bidirectional label dependencies.
         *
         * @param desc the node label existence constraint descriptor
         * @throws IllegalStateException if a duplicate coexistence constraint is detected
         */
        @Override
        public void accumulateLabelCoexistence(final ConstraintDescriptor desc) {
            hasAny = true;
            final var nlDesc = desc.asNodeLabelExistenceConstraint();
            final int schemaLabelId = nlDesc.schema().getLabelId();
            final int requiredLabelId = nlDesc.requiredLabelId();

            final var existing = coexistentLabels.getOrDefault(schemaLabelId, Set.of());
            Preconditions.checkState(
                    !existing.contains(requiredLabelId),
                    "Duplicate label coexistence constraint: schemaLabelId=%d already requires" + " requiredLabelId=%d",
                    schemaLabelId,
                    requiredLabelId);

            coexistentLabels
                    .computeIfAbsent(schemaLabelId, k -> new HashSet<>())
                    .add(requiredLabelId);
            inverseDependencies
                    .computeIfAbsent(requiredLabelId, k -> new HashSet<>())
                    .add(schemaLabelId);
        }

        /**
         * Assembles the accumulated rules into an {@link IntegrityRuleRegistry}.
         * Returns empty if no enforceable constraints were accumulated.
         *
         * @return an optional registry
         */
        @Override
        public Optional<IntegrityRuleRegistry> assemble() {
            if (!hasAny) {
                return Optional.empty();
            }

            final var attrRules = new AttributeRules(
                    vertexMandatoryAttributes, vertexTypeConstraints, edgeMandatoryAttributes, edgeTypeConstraints);

            final var coexRules = new LabelCoexistenceRules(coexistentLabels, inverseDependencies);

            final var epRules = new EndpointBindingRules(
                    mandatedOriginLabels,
                    mandatedTerminusLabels,
                    edgeOriginLabels,
                    edgeTerminusLabels,
                    topologyBoundEdgeTypes);

            return Optional.of(new IntegrityRuleRegistry(attrRules, coexRules, epRules));
        }
    }

    // --- Accessor Methods ---

    /**
     * Returns the set of mandatory property keys for a given vertex label.
     *
     * @param labelId the label token id
     * @return the set of required property key ids, or null if none
     */
    Set<Integer> requiredVertexPropertyTokens(final int labelId) {
        return attributeRules.vertexMandatoryAttributes().get(labelId);
    }

    /**
     * Returns the property type constraint map for a given vertex label.
     *
     * @param labelId the label token id
     * @return a map of property key id to allowed type set, or null if none
     */
    Map<Integer, PropertyTypeSet> vertexTypeConstraints(final int labelId) {
        return attributeRules.vertexTypeConstraints().get(labelId);
    }

    /**
     * Returns the set of labels that must coexist with the given schema label.
     *
     * @param schemaLabel the schema label token id
     * @return the set of required label ids, or null if none
     */
    Set<Integer> coexistentLabels(final int schemaLabel) {
        return labelCoexistenceRules.coexistentLabels().get(schemaLabel);
    }

    /**
     * Returns the set of labels that depend on the given required label.
     *
     * @param requiredLabel the required label token id
     * @return the set of dependent label ids, or null if none
     */
    Set<Integer> inverseDependencies(final int requiredLabel) {
        return labelCoexistenceRules.inverseDependencies().get(requiredLabel);
    }

    /**
     * Returns whether any label coexistence rules exist.
     *
     * @return true if there are coexistence rules
     */
    boolean hasCoexistenceRules() {
        return !labelCoexistenceRules.coexistentLabels().isEmpty();
    }

    /**
     * Returns the required origin (start-node) label for a relationship type,
     * or -1 if no constraint exists.
     *
     * @param relTypeId the relationship type token id
     * @return the required label id, or -1
     */
    int mandatedOriginLabel(final int relTypeId) {
        return endpointBindingRules.mandatedOriginLabels().getOrDefault(relTypeId, -1);
    }

    /**
     * Returns the required terminus (end-node) label for a relationship type,
     * or -1 if no constraint exists.
     *
     * @param relTypeId the relationship type token id
     * @return the required label id, or -1
     */
    int mandatedTerminusLabel(final int relTypeId) {
        return endpointBindingRules.mandatedTerminusLabels().getOrDefault(relTypeId, -1);
    }

    /**
     * Returns whether any endpoint binding rules (topology rules) exist.
     *
     * @return true if origin or terminus label requirements exist
     */
    boolean hasEndpointBindings() {
        return !endpointBindingRules.mandatedOriginLabels().isEmpty()
                || !endpointBindingRules.mandatedTerminusLabels().isEmpty();
    }

    /**
     * Returns the set of relationship types that have topology constraints.
     *
     * @return the set of constrained relationship type ids
     */
    Set<Integer> topologyBoundEdgeTypes() {
        return endpointBindingRules.topologyBoundEdgeTypes();
    }

    /**
     * Returns the origin label bindings map (relTypeId -> required source label).
     *
     * @return the origin label bindings
     */
    Map<Integer, Integer> originLabelBindings() {
        return endpointBindingRules.mandatedOriginLabels();
    }

    /**
     * Returns the terminus label bindings map (relTypeId -> required target label).
     *
     * @return the terminus label bindings
     */
    Map<Integer, Integer> terminusLabelBindings() {
        return endpointBindingRules.mandatedTerminusLabels();
    }

    /**
     * Returns the set of mandatory property keys for a given edge (relationship) type.
     *
     * @param relTypeId the relationship type token id
     * @return the set of required property key ids, or null if none
     */
    Set<Integer> requiredEdgePropertyTokens(final int relTypeId) {
        return attributeRules.edgeMandatoryAttributes().get(relTypeId);
    }

    /**
     * Returns the property type constraint map for a given edge (relationship) type.
     *
     * @param relTypeId the relationship type token id
     * @return a map of property key id to allowed type set, or null if none
     */
    Map<Integer, PropertyTypeSet> edgeTypeConstraints(final int relTypeId) {
        return attributeRules.edgeTypeConstraints().get(relTypeId);
    }

    /**
     * Returns whether any edge endpoint binding rules exist for relationship creation.
     *
     * @return true if edge origin or terminus label requirements exist
     */
    boolean hasEdgeEndpointBindings() {
        return !endpointBindingRules.edgeOriginLabels().isEmpty()
                || !endpointBindingRules.edgeTerminusLabels().isEmpty();
    }

    /**
     * Returns the edge origin label bindings map (relTypeId -> required source label).
     *
     * @return the edge origin label bindings
     */
    Map<Integer, Integer> edgeOriginLabels() {
        return endpointBindingRules.edgeOriginLabels();
    }

    /**
     * Returns the edge terminus label bindings map (relTypeId -> required target label).
     *
     * @return the edge terminus label bindings
     */
    Map<Integer, Integer> edgeTerminusLabels() {
        return endpointBindingRules.edgeTerminusLabels();
    }
}
