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

import static org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException.Phase.VALIDATION;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.EndpointType;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.RelationTypeSchemaDescriptor;
import org.neo4j.kernel.api.exceptions.schema.NodeLabelExistenceMissingLabelException;
import org.neo4j.kernel.api.exceptions.schema.NodePropertyExistenceException;
import org.neo4j.kernel.api.exceptions.schema.PropertyTypeException;
import org.neo4j.kernel.api.exceptions.schema.RelationshipEndpointLabelMissingLabelException;
import org.neo4j.kernel.api.exceptions.schema.RelationshipPropertyExistenceException;
import org.neo4j.values.storable.Value;

/**
 * Catalogs constraint descriptors by fault domain and provides methods to emit
 * validation exceptions when constraint violations are detected.
 *
 * <p>Uses bit-packing to encode entity/property/endpoint identifiers into compact
 * lookup keys for O(1) constraint descriptor resolution.</p>
 */
final class ValidationFaultEmitter {

    /**
     * Classifies the domain of a constraint violation for efficient lookup.
     */
    enum FaultDomain {
        /** A vertex (node) is missing a required attribute (property). */
        VERTEX_ATTRIBUTE_ABSENCE,

        /** A vertex (node) property value does not match the required type. */
        VERTEX_TYPE_MISMATCH,

        /** An edge (relationship) is missing a required attribute (property). */
        EDGE_ATTRIBUTE_ABSENCE,

        /** An edge (relationship) property value does not match the required type. */
        EDGE_TYPE_MISMATCH,

        /** A relationship endpoint node is missing a required label. */
        ENDPOINT_LABEL_DEFICIT,

        /** A node is missing a label required by a coexistence constraint. */
        LABEL_COREQUISITE_DEFICIT
    }

    /**
     * Encodes two integer identifiers into a single long key by placing the high
     * value in the upper 32 bits and the low value in the lower 32 bits.
     *
     * @param high the upper identifier (e.g., label or relationship type id)
     * @param low the lower identifier (e.g., property key id)
     * @return the packed long key
     */
    private static long encodeAttributePair(final int high, final int low) {
        return ((long) high << 32) | (low & 0xFFFFFFFFL);
    }

    /**
     * Encodes three integer identifiers into a single long key using bit-field
     * partitioning: 21 bits for a, 21 bits for b, and 22 bits for c.
     *
     * @param a the first identifier (e.g., relationship type id)
     * @param b the second identifier (e.g., endpoint type ordinal)
     * @param c the third identifier (e.g., endpoint label id)
     * @return the packed long key
     */
    private static long encodeEndpointTriad(final int a, final int b, final int c) {
        return ((long) a << 43) | ((long) b << 22) | (c & 0x3FFFFFL);
    }

    private final EnumMap<FaultDomain, Map<Long, ConstraintDescriptor>> domainIndex;
    private final TokenNameLookup tokenNameLookup;

    /**
     * Creates a new fault emitter by cataloging all constraint descriptors
     * into domain-specific lookup maps.
     *
     * @param descriptors the constraint descriptors to catalog
     * @param tokenNameLookup the token name lookup for formatting error messages
     */
    ValidationFaultEmitter(final Iterator<ConstraintDescriptor> descriptors, final TokenNameLookup tokenNameLookup) {
        this.tokenNameLookup = tokenNameLookup;
        this.domainIndex = new EnumMap<>(FaultDomain.class);
        for (final FaultDomain domain : FaultDomain.values()) {
            domainIndex.put(domain, new HashMap<>());
        }
        catalogAllDescriptors(descriptors);
    }

    /**
     * Iterates through all constraint descriptors and indexes them by fault domain
     * and packed key for fast lookup during violation reporting.
     *
     * @param descriptors the constraint descriptors to catalog
     */
    private void catalogAllDescriptors(final Iterator<ConstraintDescriptor> descriptors) {
        while (descriptors.hasNext()) {
            final var desc = descriptors.next();

            switch (desc.type()) {
                case UNIQUE -> {
                    // Uniqueness is handled by index infrastructure
                }

                case EXISTS, UNIQUE_EXISTS -> {
                    switch (desc.schema().entityType()) {
                        case NODE -> {
                            final int labelId = desc.schema().getLabelId();
                            for (final int propId : desc.schema().getPropertyIds()) {
                                domainIndex
                                        .get(FaultDomain.VERTEX_ATTRIBUTE_ABSENCE)
                                        .put(encodeAttributePair(labelId, propId), desc);
                            }
                        }
                        case RELATIONSHIP -> {
                            final int relTypeId = desc.schema().getRelTypeId();
                            for (final int propId : desc.schema().getPropertyIds()) {
                                domainIndex
                                        .get(FaultDomain.EDGE_ATTRIBUTE_ABSENCE)
                                        .put(encodeAttributePair(relTypeId, propId), desc);
                            }
                        }
                    }
                }

                case PROPERTY_TYPE -> {
                    switch (desc.schema().entityType()) {
                        case NODE -> {
                            final int labelId = desc.schema().getLabelId();
                            for (final int propId : desc.schema().getPropertyIds()) {
                                domainIndex
                                        .get(FaultDomain.VERTEX_TYPE_MISMATCH)
                                        .put(encodeAttributePair(labelId, propId), desc);
                            }
                        }
                        case RELATIONSHIP -> {
                            final int relTypeId = desc.schema().getRelTypeId();
                            for (final int propId : desc.schema().getPropertyIds()) {
                                domainIndex
                                        .get(FaultDomain.EDGE_TYPE_MISMATCH)
                                        .put(encodeAttributePair(relTypeId, propId), desc);
                            }
                        }
                    }
                }

                case RELATIONSHIP_ENDPOINT_LABEL -> {
                    final var epDesc = desc.asRelationshipEndpointLabelConstraint();
                    final int relTypeId = epDesc.schema().getRelTypeId();
                    final int epLabelId = epDesc.endpointLabelId();
                    final EndpointType endpoint = epDesc.endpointType();
                    domainIndex
                            .get(FaultDomain.ENDPOINT_LABEL_DEFICIT)
                            .put(encodeEndpointTriad(relTypeId, endpoint.ordinal(), epLabelId), desc);
                }

                case NODE_LABEL_EXISTENCE -> {
                    final var nlDesc = desc.asNodeLabelExistenceConstraint();
                    final int schemaLabelId = nlDesc.schema().getLabelId();
                    final int requiredLabelId = nlDesc.requiredLabelId();
                    domainIndex
                            .get(FaultDomain.LABEL_COREQUISITE_DEFICIT)
                            .put(encodeAttributePair(schemaLabelId, requiredLabelId), desc);
                }
            }
        }
    }

    /**
     * Looks up the constraint descriptor for a given fault domain and encoded key.
     *
     * @param domain the fault domain
     * @param key the encoded lookup key
     * @return the matching constraint descriptor
     * @throws IllegalStateException if no matching constraint is found
     */
    private ConstraintDescriptor lookup(final FaultDomain domain, final long key) {
        final var desc = domainIndex.get(domain).get(key);
        if (desc == null) {
            throw new IllegalStateException("No matching " + domain + " constraint for key=" + key);
        }
        return desc;
    }

    /**
     * Emits a vertex attribute absence fault (missing required node property).
     *
     * @param labelId the label token id
     * @param nodeId the node entity id
     * @param propertyKeyId the missing property key id
     * @throws NodePropertyExistenceException always thrown to signal the violation
     */
    void emitVertexAttributeAbsence(final int labelId, final long nodeId, final int propertyKeyId)
            throws NodePropertyExistenceException {
        final var desc = lookup(FaultDomain.VERTEX_ATTRIBUTE_ABSENCE, encodeAttributePair(labelId, propertyKeyId));
        final var labelSchema = (LabelSchemaDescriptor) desc.schema();
        throw NodePropertyExistenceException.propertyPresenceViolation(
                labelSchema, tokenNameLookup, desc, VALIDATION, nodeId);
    }

    /**
     * Emits a vertex type fault (node property value does not match the constraint type).
     *
     * @param labelId the label token id
     * @param nodeId the node entity id
     * @param propertyKeyId the offending property key id
     * @param value the offending property value
     * @throws PropertyTypeException always thrown to signal the violation
     */
    void emitVertexTypeFault(final int labelId, final long nodeId, final int propertyKeyId, final Value value)
            throws PropertyTypeException {
        final var desc = lookup(FaultDomain.VERTEX_TYPE_MISMATCH, encodeAttributePair(labelId, propertyKeyId));
        final var typeDesc = desc.asPropertyTypeConstraint();
        throw PropertyTypeException.propertyTypeValidationFailed(typeDesc, nodeId, tokenNameLookup, value);
    }

    /**
     * Emits an edge attribute absence fault (missing required relationship property).
     *
     * @param relId the relationship entity id
     * @param propertyKeyId the missing property key id
     * @param relTypeId the relationship type token id
     * @throws RelationshipPropertyExistenceException always thrown to signal the violation
     */
    void emitEdgeAttributeAbsence(final long relId, final int propertyKeyId, final int relTypeId)
            throws RelationshipPropertyExistenceException {
        final var desc = lookup(FaultDomain.EDGE_ATTRIBUTE_ABSENCE, encodeAttributePair(relTypeId, propertyKeyId));
        final var relSchema = (RelationTypeSchemaDescriptor) desc.schema();
        throw RelationshipPropertyExistenceException.propertyPresenceViolation(
                relSchema, tokenNameLookup, desc, VALIDATION, relId);
    }

    /**
     * Emits an edge type fault (relationship property value does not match the constraint type).
     *
     * @param propertyKeyId the offending property key id
     * @param relId the relationship entity id
     * @param relTypeId the relationship type token id
     * @param value the offending property value
     * @throws PropertyTypeException always thrown to signal the violation
     */
    void emitEdgeTypeFault(final int propertyKeyId, final long relId, final int relTypeId, final Value value)
            throws PropertyTypeException {
        final var desc = lookup(FaultDomain.EDGE_TYPE_MISMATCH, encodeAttributePair(relTypeId, propertyKeyId));
        final var typeDesc = desc.asPropertyTypeConstraint();
        throw PropertyTypeException.propertyTypeValidationFailed(typeDesc, relId, tokenNameLookup, value);
    }

    /**
     * Emits an endpoint label deficit fault (relationship endpoint node is missing a required label).
     *
     * @param endpoint the endpoint type (START or END)
     * @param labelId the missing label id
     * @param nodeId the endpoint node entity id
     * @param relId the relationship entity id
     * @param relTypeId the relationship type token id
     * @throws RelationshipEndpointLabelMissingLabelException always thrown to signal the violation
     */
    void emitEndpointLabelDeficit(
            final EndpointType endpoint, final int labelId, final long nodeId, final long relId, final int relTypeId)
            throws RelationshipEndpointLabelMissingLabelException {
        final var desc =
                lookup(FaultDomain.ENDPOINT_LABEL_DEFICIT, encodeEndpointTriad(relTypeId, endpoint.ordinal(), labelId));
        final var epDesc = desc.asRelationshipEndpointLabelConstraint();
        throw RelationshipEndpointLabelMissingLabelException.endpointLabelPresenceVerificationFailed(
                epDesc, VALIDATION, relId, nodeId, tokenNameLookup);
    }

    /**
     * Emits a label corequisite deficit fault (node is missing a label required by a coexistence constraint).
     *
     * @param missingLabelId the missing required label id
     * @param nodeId the node entity id
     * @param schemaLabelId the schema label that requires the missing label
     * @throws NodeLabelExistenceMissingLabelException always thrown to signal the violation
     */
    void emitLabelCorequisiteDeficit(final int missingLabelId, final long nodeId, final int schemaLabelId)
            throws NodeLabelExistenceMissingLabelException {
        final var desc =
                lookup(FaultDomain.LABEL_COREQUISITE_DEFICIT, encodeAttributePair(schemaLabelId, missingLabelId));
        final var nlDesc = desc.asNodeLabelExistenceConstraint();
        throw NodeLabelExistenceMissingLabelException.tokenPresenceVerificationFailed(
                nlDesc, VALIDATION, nodeId, tokenNameLookup);
    }
}
