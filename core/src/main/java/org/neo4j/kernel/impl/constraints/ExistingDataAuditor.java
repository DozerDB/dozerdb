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

import java.util.Arrays;
import java.util.function.Function;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.EntityCursor;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.NodeLabelIndexCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.RelationshipDataAccessor;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.RelationshipTypeIndexCursor;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.EndpointType;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.schema.constraints.ConstraintDescriptorFactory;
import org.neo4j.internal.schema.constraints.NodeLabelExistenceConstraintDescriptor;
import org.neo4j.internal.schema.constraints.RelationshipEndpointLabelConstraintDescriptor;
import org.neo4j.internal.schema.constraints.TypeConstraintDescriptor;
import org.neo4j.internal.schema.constraints.TypeRepresentation;
import org.neo4j.kernel.api.exceptions.schema.NodeLabelExistenceMissingLabelException;
import org.neo4j.kernel.api.exceptions.schema.NodePropertyExistenceException;
import org.neo4j.kernel.api.exceptions.schema.PropertyTypeException;
import org.neo4j.kernel.api.exceptions.schema.RelationshipEndpointLabelMissingLabelException;
import org.neo4j.kernel.api.exceptions.schema.RelationshipPropertyExistenceException;
import org.neo4j.storageengine.api.PropertySelection;
import org.neo4j.values.storable.Value;

/**
 * Audits existing data in the database to verify compliance with constraints
 * during constraint creation. All methods are static; the class is not instantiable.
 *
 * <p>This auditor is invoked when a new constraint is being created to ensure that
 * all existing entities in the database satisfy the new constraint before it is
 * committed to the schema.</p>
 */
final class ExistingDataAuditor {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ExistingDataAuditor() {}

    // ========================================================================
    // --- Sealed Constraint Factory Interface ---
    // ========================================================================

    /**
     * A sealed interface representing a strategy for creating constraint descriptors
     * from schema descriptors. Used to abstract over existence vs. key constraint
     * creation during data auditing.
     */
    sealed interface ConstraintFactory {

        /**
         * Creates a label-based constraint descriptor.
         *
         * @param schema the label schema descriptor
         * @return the constraint descriptor
         */
        ConstraintDescriptor fromLabelSchema(LabelSchemaDescriptor schema);

        /**
         * Creates a relationship-type-based constraint descriptor.
         *
         * @param schema the relation type schema descriptor
         * @return the constraint descriptor
         */
        ConstraintDescriptor fromRelSchema(RelationTypeSchemaDescriptor schema);
    }

    /**
     * A constraint factory that creates existence constraints.
     *
     * @param isDependent whether the existence constraint is dependent
     */
    record ExistenceConstraintFactory(boolean isDependent) implements ConstraintFactory {

        /**
         * Creates an existence constraint for a label schema.
         *
         * @param schema the label schema descriptor
         * @return the existence constraint descriptor
         */
        @Override
        public ConstraintDescriptor fromLabelSchema(final LabelSchemaDescriptor schema) {
            return ConstraintDescriptorFactory.existsForSchema(schema, isDependent);
        }

        /**
         * Creates an existence constraint for a relation type schema.
         *
         * @param schema the relation type schema descriptor
         * @return the existence constraint descriptor
         */
        @Override
        public ConstraintDescriptor fromRelSchema(final RelationTypeSchemaDescriptor schema) {
            return ConstraintDescriptorFactory.existsForSchema(schema, isDependent);
        }
    }

    /**
     * A constraint factory that creates key constraints.
     */
    record KeyConstraintFactory() implements ConstraintFactory {

        /**
         * Creates a key constraint for a label schema.
         *
         * @param schema the label schema descriptor
         * @return the key constraint descriptor
         */
        @Override
        public ConstraintDescriptor fromLabelSchema(final LabelSchemaDescriptor schema) {
            return ConstraintDescriptorFactory.keyForSchema(schema);
        }

        /**
         * Creates a key constraint for a relation type schema.
         *
         * @param schema the relation type schema descriptor
         * @return the key constraint descriptor
         */
        @Override
        public ConstraintDescriptor fromRelSchema(final RelationTypeSchemaDescriptor schema) {
            return ConstraintDescriptorFactory.keyForSchema(schema);
        }
    }

    // ========================================================================
    // --- Vertex (Node) Attribute Presence Audits ---
    // ========================================================================

    /**
     * Audits vertex attribute presence using an index cursor to iterate label-indexed nodes.
     *
     * @param indexCursor the label index cursor positioned for scanning
     * @param nodeCursor the node cursor for reading node data
     * @param propertyCursor the property cursor for reading properties
     * @param labelSchema the label schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param isDependent whether the constraint is dependent
     * @throws CreateConstraintFailureException if a node violates the constraint
     */
    static void auditVertexAttributePresence(
            final NodeLabelIndexCursor indexCursor,
            final NodeCursor nodeCursor,
            final PropertyCursor propertyCursor,
            final LabelSchemaDescriptor labelSchema,
            final TokenNameLookup tokenNames,
            final boolean isDependent)
            throws CreateConstraintFailureException {
        while (indexCursor.next()) {
            indexCursor.node(nodeCursor);
            auditVertexAttributePresenceInternal(
                    nodeCursor,
                    propertyCursor,
                    labelSchema,
                    tokenNames,
                    schema -> ConstraintDescriptorFactory.existsForSchema(schema, isDependent));
        }
    }

    /**
     * Audits vertex attribute presence using a direct node cursor scan.
     *
     * @param nodeCursor the node cursor for reading node data
     * @param propertyCursor the property cursor for reading properties
     * @param labelSchema the label schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param isDependent whether the constraint is dependent
     * @throws CreateConstraintFailureException if a node violates the constraint
     */
    static void auditVertexAttributePresence(
            final NodeCursor nodeCursor,
            final PropertyCursor propertyCursor,
            final LabelSchemaDescriptor labelSchema,
            final TokenNameLookup tokenNames,
            final boolean isDependent)
            throws CreateConstraintFailureException {
        auditVertexAttributePresenceInternal(
                nodeCursor,
                propertyCursor,
                labelSchema,
                tokenNames,
                schema -> ConstraintDescriptorFactory.existsForSchema(schema, isDependent));
    }

    /**
     * Audits vertex key presence using an index cursor to iterate label-indexed nodes.
     *
     * @param indexCursor the label index cursor positioned for scanning
     * @param nodeCursor the node cursor for reading node data
     * @param propertyCursor the property cursor for reading properties
     * @param labelSchema the label schema defining key properties
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a node violates the constraint
     */
    static void auditVertexKeyPresence(
            final NodeLabelIndexCursor indexCursor,
            final NodeCursor nodeCursor,
            final PropertyCursor propertyCursor,
            final LabelSchemaDescriptor labelSchema,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        while (indexCursor.next()) {
            indexCursor.node(nodeCursor);
            auditVertexAttributePresenceInternal(
                    nodeCursor, propertyCursor, labelSchema, tokenNames, ConstraintDescriptorFactory::keyForSchema);
        }
    }

    /**
     * Audits vertex key presence using a direct node cursor scan.
     *
     * @param nodeCursor the node cursor for reading node data
     * @param propertyCursor the property cursor for reading properties
     * @param labelSchema the label schema defining key properties
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a node violates the constraint
     */
    static void auditVertexKeyPresence(
            final NodeCursor nodeCursor,
            final PropertyCursor propertyCursor,
            final LabelSchemaDescriptor labelSchema,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        auditVertexAttributePresenceInternal(
                nodeCursor, propertyCursor, labelSchema, tokenNames, ConstraintDescriptorFactory::keyForSchema);
    }

    // ========================================================================
    // --- Edge (Relationship) Attribute Presence Audits ---
    // ========================================================================

    /**
     * Audits edge attribute presence using a relationship type index cursor.
     *
     * @param indexCursor the relationship type index cursor
     * @param propCursor the property cursor for reading properties
     * @param relSchema the relation type schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param isDependent whether the constraint is dependent
     * @throws CreateConstraintFailureException if a relationship violates the constraint
     */
    static void auditEdgeAttributePresence(
            final RelationshipTypeIndexCursor indexCursor,
            final PropertyCursor propCursor,
            final RelationTypeSchemaDescriptor relSchema,
            final TokenNameLookup tokenNames,
            final boolean isDependent)
            throws CreateConstraintFailureException {
        auditEdgeAttributePresenceViaIndex(
                indexCursor,
                propCursor,
                relSchema,
                tokenNames,
                schema -> ConstraintDescriptorFactory.existsForSchema(schema, isDependent));
    }

    /**
     * Audits edge attribute presence using a relationship scan cursor.
     *
     * @param scanCursor the relationship scan cursor
     * @param propCursor the property cursor for reading properties
     * @param relSchema the relation type schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param isDependent whether the constraint is dependent
     * @throws CreateConstraintFailureException if a relationship violates the constraint
     */
    static void auditEdgeAttributePresence(
            final RelationshipScanCursor scanCursor,
            final PropertyCursor propCursor,
            final RelationTypeSchemaDescriptor relSchema,
            final TokenNameLookup tokenNames,
            final boolean isDependent)
            throws CreateConstraintFailureException {
        auditEdgeAttributePresenceViaScan(
                scanCursor,
                propCursor,
                relSchema,
                tokenNames,
                schema -> ConstraintDescriptorFactory.existsForSchema(schema, isDependent));
    }

    /**
     * Audits edge key presence using a relationship type index cursor.
     *
     * @param indexCursor the relationship type index cursor
     * @param scanCursor the relationship scan cursor (unused but kept for API compatibility)
     * @param propCursor the property cursor for reading properties
     * @param relSchema the relation type schema defining key properties
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a relationship violates the constraint
     */
    static void auditEdgeKeyPresence(
            final RelationshipTypeIndexCursor indexCursor,
            final RelationshipScanCursor scanCursor,
            final PropertyCursor propCursor,
            final RelationTypeSchemaDescriptor relSchema,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        auditEdgeAttributePresenceViaIndex(
                indexCursor, propCursor, relSchema, tokenNames, ConstraintDescriptorFactory::keyForSchema);
    }

    /**
     * Audits edge key presence using a relationship scan cursor.
     *
     * @param scanCursor the relationship scan cursor
     * @param propCursor the property cursor for reading properties
     * @param relSchema the relation type schema defining key properties
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a relationship violates the constraint
     */
    static void auditEdgeKeyPresence(
            final RelationshipScanCursor scanCursor,
            final PropertyCursor propCursor,
            final RelationTypeSchemaDescriptor relSchema,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        auditEdgeAttributePresenceViaScan(
                scanCursor, propCursor, relSchema, tokenNames, ConstraintDescriptorFactory::keyForSchema);
    }

    // ========================================================================
    // --- Type Conformance Audits ---
    // ========================================================================

    /**
     * Audits vertex type conformance using an index cursor.
     *
     * @param indexCursor the label index cursor
     * @param nodeCursor the node cursor for reading node data
     * @param propCursor the property cursor for reading properties
     * @param typeDescriptor the type constraint to verify
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a node violates the type constraint
     */
    static void auditVertexTypeConformance(
            final NodeLabelIndexCursor indexCursor,
            final NodeCursor nodeCursor,
            final PropertyCursor propCursor,
            final TypeConstraintDescriptor typeDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        while (indexCursor.next()) {
            indexCursor.node(nodeCursor);
            auditVertexTypeConformance(nodeCursor, propCursor, typeDescriptor, tokenNames);
        }
    }

    /**
     * Audits vertex type conformance using a direct node cursor scan.
     *
     * @param nodeCursor the node cursor for reading node data
     * @param propCursor the property cursor for reading properties
     * @param typeDescriptor the type constraint to verify
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a node violates the type constraint
     */
    static void auditVertexTypeConformance(
            final NodeCursor nodeCursor,
            final PropertyCursor propCursor,
            final TypeConstraintDescriptor typeDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        while (nodeCursor.next()) {
            verifyEntityTypeCompliance(nodeCursor, propCursor, tokenNames, typeDescriptor);
        }
    }

    /**
     * Audits edge type conformance using a relationship type index cursor.
     *
     * @param indexCursor the relationship type index cursor
     * @param propCursor the property cursor for reading properties
     * @param typeDescriptor the type constraint to verify
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a relationship violates the type constraint
     */
    static void auditEdgeTypeConformance(
            final RelationshipTypeIndexCursor indexCursor,
            final PropertyCursor propCursor,
            final TypeConstraintDescriptor typeDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        while (indexCursor.next()) {
            if (indexCursor.readFromStore()) {
                verifyEntityTypeCompliance(indexCursor, propCursor, tokenNames, typeDescriptor);
            }
        }
    }

    /**
     * Audits edge type conformance using a relationship scan cursor.
     *
     * @param scanCursor the relationship scan cursor
     * @param propCursor the property cursor for reading properties
     * @param typeDescriptor the type constraint to verify
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a relationship violates the type constraint
     */
    static void auditEdgeTypeConformance(
            final RelationshipScanCursor scanCursor,
            final PropertyCursor propCursor,
            final TypeConstraintDescriptor typeDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        while (scanCursor.next()) {
            verifyEntityTypeCompliance(scanCursor, propCursor, tokenNames, typeDescriptor);
        }
    }

    // ========================================================================
    // --- Endpoint Label Audits ---
    // ========================================================================

    /**
     * Audits endpoint label presence using a relationship type index cursor.
     *
     * @param indexCursor the relationship type index cursor
     * @param nodeCursor the node cursor for endpoint verification
     * @param epDescriptor the endpoint label constraint descriptor
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a relationship violates the constraint
     */
    static void auditEndpointLabelPresence(
            final RelationshipTypeIndexCursor indexCursor,
            final NodeCursor nodeCursor,
            final RelationshipEndpointLabelConstraintDescriptor epDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        final EndpointType epType = epDescriptor.endpointType();
        final int epLabelId = epDescriptor.endpointLabelId();
        if (epType == null) {
            throw new IllegalArgumentException("Null endpoint type in constraint descriptor: " + epDescriptor);
        }

        assert !indexCursor.isClosed();

        while (indexCursor.next()) {
            if (indexCursor.readFromStore()) {
                verifyEndpointNodeLabel(epLabelId, epType, nodeCursor, indexCursor, epDescriptor, tokenNames);
            }
        }
    }

    /**
     * Audits endpoint label presence using a relationship scan cursor.
     *
     * @param scanCursor the relationship scan cursor
     * @param nodeCursor the node cursor for endpoint verification
     * @param epDescriptor the endpoint label constraint descriptor
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a relationship violates the constraint
     */
    static void auditEndpointLabelPresence(
            final RelationshipScanCursor scanCursor,
            final NodeCursor nodeCursor,
            final RelationshipEndpointLabelConstraintDescriptor epDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        final EndpointType epType = epDescriptor.endpointType();
        final int epLabelId = epDescriptor.endpointLabelId();
        if (epType == null) {
            throw new IllegalArgumentException("Null endpoint type in constraint descriptor: " + epDescriptor);
        }

        assert !scanCursor.isClosed();

        while (scanCursor.next()) {
            verifyEndpointNodeLabel(epLabelId, epType, nodeCursor, scanCursor, epDescriptor, tokenNames);
        }
    }

    // ========================================================================
    // --- Label Coexistence Audits ---
    // ========================================================================

    /**
     * Audits label coexistence using a label index cursor.
     *
     * @param indexCursor the label index cursor
     * @param nodeCursor the node cursor for reading node data
     * @param nlDescriptor the node label existence constraint descriptor
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a node violates the constraint
     */
    static void auditLabelCoexistence(
            final NodeLabelIndexCursor indexCursor,
            final NodeCursor nodeCursor,
            final NodeLabelExistenceConstraintDescriptor nlDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        while (indexCursor.next()) {
            indexCursor.node(nodeCursor);
            auditLabelCoexistence(nodeCursor, nlDescriptor, tokenNames);
        }
    }

    /**
     * Audits label coexistence using a direct node cursor scan.
     *
     * @param nodeCursor the node cursor for reading node data
     * @param nlDescriptor the node label existence constraint descriptor
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if a node violates the constraint
     */
    static void auditLabelCoexistence(
            final NodeCursor nodeCursor,
            final NodeLabelExistenceConstraintDescriptor nlDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        final int requiredLabel = nlDescriptor.requiredLabelId();

        assert !nodeCursor.isClosed();

        while (nodeCursor.next()) {
            assert nodeCursor.hasLabel(nlDescriptor.schema().getLabelId());

            if (!nodeCursor.hasLabel(requiredLabel)) {
                throw CreateConstraintFailureException.constraintCreationFailed(
                        new NodeLabelExistenceMissingLabelException(
                                nlDescriptor,
                                ConstraintValidationException.Phase.VERIFICATION,
                                nodeCursor.reference(),
                                tokenNames),
                        tokenNames);
            }
        }
    }

    // ========================================================================
    // --- Private Helper Methods ---
    // ========================================================================

    /**
     * Checks whether an entity possesses all required property keys by
     * performing a sorted array binary search for each discovered property.
     *
     * @param requiredKeyIds the array of required property key ids
     * @param entity the entity cursor to inspect
     * @param props the property cursor for iterating properties
     * @return true if all required keys are present
     */
    private static boolean entitySatisfiesAllKeys(
            final int[] requiredKeyIds, final EntityCursor entity, final PropertyCursor props) {
        entity.properties(props, PropertySelection.onlyKeysSelection(requiredKeyIds));
        final int[] sorted = requiredKeyIds.clone();
        Arrays.sort(sorted);
        int found = 0;
        while (props.next()) {
            if (Arrays.binarySearch(sorted, props.propertyKey()) >= 0) {
                found++;
            }
        }
        return found >= sorted.length;
    }

    /**
     * Internal implementation for auditing vertex attribute presence.
     * Iterates through nodes and verifies all required properties exist.
     *
     * @param nodeCursor the node cursor positioned for reading
     * @param propertyCursor the property cursor for reading properties
     * @param labelSchema the label schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param constraintBuilder a function that creates the appropriate constraint descriptor
     * @throws CreateConstraintFailureException if a node is missing required properties
     */
    private static void auditVertexAttributePresenceInternal(
            final NodeCursor nodeCursor,
            final PropertyCursor propertyCursor,
            final LabelSchemaDescriptor labelSchema,
            final TokenNameLookup tokenNames,
            final Function<LabelSchemaDescriptor, ConstraintDescriptor> constraintBuilder)
            throws CreateConstraintFailureException {
        while (nodeCursor.next()) {
            if (!entitySatisfiesAllKeys(labelSchema.getPropertyIds(), nodeCursor, propertyCursor)) {
                throw CreateConstraintFailureException.constraintCreationFailed(
                        NodePropertyExistenceException.propertyPresenceViolation(
                                labelSchema,
                                tokenNames,
                                constraintBuilder.apply(labelSchema),
                                ConstraintValidationException.Phase.VERIFICATION,
                                nodeCursor.nodeReference()),
                        tokenNames);
            }
        }
    }

    /**
     * Audits edge attribute presence via relationship type index cursor.
     *
     * @param indexCursor the relationship type index cursor
     * @param propCursor the property cursor for reading properties
     * @param relSchema the relation type schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param constraintBuilder a function that creates the appropriate constraint descriptor
     * @throws CreateConstraintFailureException if a relationship is missing required properties
     */
    private static void auditEdgeAttributePresenceViaIndex(
            final RelationshipTypeIndexCursor indexCursor,
            final PropertyCursor propCursor,
            final RelationTypeSchemaDescriptor relSchema,
            final TokenNameLookup tokenNames,
            final Function<RelationTypeSchemaDescriptor, ConstraintDescriptor> constraintBuilder)
            throws CreateConstraintFailureException {
        while (indexCursor.next()) {
            if (indexCursor.readFromStore()) {
                if (!entitySatisfiesAllKeys(relSchema.getPropertyIds(), indexCursor, propCursor)) {
                    throw CreateConstraintFailureException.constraintCreationFailed(
                            RelationshipPropertyExistenceException.propertyPresenceViolation(
                                    relSchema,
                                    tokenNames,
                                    constraintBuilder.apply(relSchema),
                                    ConstraintValidationException.Phase.VERIFICATION,
                                    indexCursor.relationshipReference()),
                            tokenNames);
                }
            }
        }
    }

    /**
     * Audits edge attribute presence via relationship scan cursor.
     *
     * @param scanCursor the relationship scan cursor
     * @param propCursor the property cursor for reading properties
     * @param relSchema the relation type schema defining required properties
     * @param tokenNames the token name lookup for error messages
     * @param constraintBuilder a function that creates the appropriate constraint descriptor
     * @throws CreateConstraintFailureException if a relationship is missing required properties
     */
    private static void auditEdgeAttributePresenceViaScan(
            final RelationshipScanCursor scanCursor,
            final PropertyCursor propCursor,
            final RelationTypeSchemaDescriptor relSchema,
            final TokenNameLookup tokenNames,
            final Function<RelationTypeSchemaDescriptor, ConstraintDescriptor> constraintBuilder)
            throws CreateConstraintFailureException {
        while (scanCursor.next()) {
            if (!entitySatisfiesAllKeys(relSchema.getPropertyIds(), scanCursor, propCursor)) {
                throw CreateConstraintFailureException.constraintCreationFailed(
                        RelationshipPropertyExistenceException.propertyPresenceViolation(
                                relSchema,
                                tokenNames,
                                constraintBuilder.apply(relSchema),
                                ConstraintValidationException.Phase.VERIFICATION,
                                scanCursor.relationshipReference()),
                        tokenNames);
            }
        }
    }

    /**
     * Verifies that an entity's property value conforms to a type constraint.
     *
     * @param entity the entity cursor to inspect
     * @param propCursor the property cursor for reading the property value
     * @param tokenNames the token name lookup for error messages
     * @param typeDescriptor the type constraint to verify against
     * @throws CreateConstraintFailureException if the property value violates the type constraint
     */
    private static void verifyEntityTypeCompliance(
            final EntityCursor entity,
            final PropertyCursor propCursor,
            final TokenNameLookup tokenNames,
            final TypeConstraintDescriptor typeDescriptor)
            throws CreateConstraintFailureException {
        entity.properties(
                propCursor, PropertySelection.selection(typeDescriptor.schema().getPropertyId()));
        if (propCursor.next()) {
            final Value value = propCursor.propertyValue();
            if (TypeRepresentation.disallows(typeDescriptor.propertyType(), value)) {
                throw CreateConstraintFailureException.constraintCreationFailed(
                        PropertyTypeException.propertyTypeVerificationFailed(
                                typeDescriptor, entity.reference(), tokenNames, value),
                        tokenNames);
            }
        }
    }

    /**
     * Verifies that a relationship endpoint node has the required label.
     *
     * @param epLabelId the required endpoint label id
     * @param epType the endpoint type (START or END)
     * @param nodeCursor the node cursor for reading endpoint node data
     * @param relAccessor the relationship data accessor for endpoint navigation
     * @param epDescriptor the endpoint label constraint descriptor
     * @param tokenNames the token name lookup for error messages
     * @throws CreateConstraintFailureException if the endpoint node is missing the label
     */
    private static void verifyEndpointNodeLabel(
            final int epLabelId,
            final EndpointType epType,
            final NodeCursor nodeCursor,
            final RelationshipDataAccessor relAccessor,
            final RelationshipEndpointLabelConstraintDescriptor epDescriptor,
            final TokenNameLookup tokenNames)
            throws CreateConstraintFailureException {
        assert relAccessor.type() == epDescriptor.schema().getRelTypeId();

        if (epType == EndpointType.START) {
            relAccessor.source(nodeCursor);
        } else if (epType == EndpointType.END) {
            relAccessor.target(nodeCursor);
        } else {
            throw new IllegalStateException("Unexpected endpoint type: " + epType + " for label " + epLabelId);
        }

        if (!nodeCursor.next()) {
            throw new IllegalStateException("Endpoint node not found for relationship " + relAccessor.reference()
                    + ", endpoint " + epType + ", label " + epLabelId);
        }

        if (!nodeCursor.hasLabel(epLabelId)) {
            throw CreateConstraintFailureException.constraintCreationFailed(
                    new RelationshipEndpointLabelMissingLabelException(
                            epDescriptor,
                            ConstraintValidationException.Phase.VERIFICATION,
                            relAccessor.reference(),
                            nodeCursor.reference(),
                            tokenNames),
                    tokenNames);
        }
    }
}
