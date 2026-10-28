/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 *  Modifications Copyright (c) DozerDB
 *  https://dozerdb.org
 */
package org.neo4j.kernel.impl.constraints;

import java.util.function.BooleanSupplier;
import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.NodeLabelIndexCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.RelationshipTypeIndexCursor;
import org.neo4j.internal.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.constraints.KeyConstraintDescriptor;
import org.neo4j.internal.schema.constraints.NodeLabelExistenceConstraintDescriptor;
import org.neo4j.internal.schema.constraints.RelationshipEndpointLabelConstraintDescriptor;
import org.neo4j.internal.schema.constraints.TypeConstraintDescriptor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.newapi.FilteringNodeCursorWrapper;
import org.neo4j.kernel.impl.newapi.FilteringRelationshipScanCursorWrapper;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;

/**
 * Constraint semantics that enable the constraint types Neo4j Community rejects. Rule creation delegates
 * to the standard rule accessor, existing data is audited by {@link ExistingDataAuditor} when a constraint
 * is created, and transaction state is enforced through {@link SchemaEnforcementProvider}.
 */
@ServiceProvider
public class DozerDbConstraintSemantics extends StandardConstraintSemantics {

    public DozerDbConstraintSemantics() {
        super(1);
    }

    @Override
    public ConstraintDescriptor readConstraint(ConstraintDescriptor constraintDescriptor) {
        return constraintDescriptor;
    }

    @Override
    public ConstraintDescriptor createKeyConstraintRule(
            KeyConstraintDescriptor keyConstraintDescriptor, long indexId, TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        return accessor.createKeyConstraintRule(keyConstraintDescriptor, indexId, tokenNameLookup);
    }

    @Override
    public ConstraintDescriptor createExistenceConstraint(
            ConstraintDescriptor constraintDescriptor, TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        return accessor.createExistenceConstraint(constraintDescriptor, tokenNameLookup);
    }

    @Override
    public ConstraintDescriptor createPropertyTypeConstraint(
            TypeConstraintDescriptor typeConstraintDescriptor, TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        return accessor.createPropertyTypeConstraint(typeConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public ConstraintDescriptor createRelationshipEndpointLabelConstraint(
            RelationshipEndpointLabelConstraintDescriptor relationshipEndpointLabelConstraintDescriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        return accessor.createRelationshipEndpointLabelConstraint(
                relationshipEndpointLabelConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public ConstraintDescriptor createNodeLabelExistenceConstraint(
            NodeLabelExistenceConstraintDescriptor nodeLabelExistenceConstraintDescriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        return accessor.createNodeLabelExistenceConstraint(nodeLabelExistenceConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void assertKeyConstraintAllowed(SchemaDescriptor schemaDescriptor, TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {}

    @Override
    public void validateNodePropertyExistenceConstraint(
            NodeLabelIndexCursor nodeLabelIndexCursor,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor labelSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent,
            MemoryTracker memoryTracker,
            BooleanSupplier doContinue)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditVertexAttributePresence(
                nodeLabelIndexCursor,
                nodeCursor,
                propertyCursor,
                labelSchemaDescriptor,
                tokenNameLookup,
                isDependent,
                doContinue);
    }

    @Override
    public void validateNodePropertyExistenceConstraint(
            FilteringNodeCursorWrapper nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor labelSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditVertexAttributePresence(
                nodeCursor, propertyCursor, labelSchemaDescriptor, tokenNameLookup, isDependent);
    }

    @Override
    public void validateNodeKeyConstraint(
            NodeLabelIndexCursor nodeLabelIndexCursor,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor labelSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditVertexKeyPresence(
                nodeLabelIndexCursor, nodeCursor, propertyCursor, labelSchemaDescriptor, tokenNameLookup);
    }

    @Override
    public void validateNodeKeyConstraint(
            FilteringNodeCursorWrapper nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor labelSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditVertexKeyPresence(nodeCursor, propertyCursor, labelSchemaDescriptor, tokenNameLookup);
    }

    @Override
    public void validateRelKeyConstraint(
            RelationshipTypeIndexCursor relationshipTypeIndexCursor,
            RelationshipScanCursor relationshipScanCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor relationTypeSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEdgeKeyPresence(
                relationshipTypeIndexCursor,
                relationshipScanCursor,
                propertyCursor,
                relationTypeSchemaDescriptor,
                tokenNameLookup);
    }

    @Override
    public void validateRelKeyConstraint(
            FilteringRelationshipScanCursorWrapper relationshipScanCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor relationTypeSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEdgeKeyPresence(
                relationshipScanCursor, propertyCursor, relationTypeSchemaDescriptor, tokenNameLookup);
    }

    @Override
    public void validateRelationshipPropertyExistenceConstraint(
            FilteringRelationshipScanCursorWrapper relationshipScanCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor relationTypeSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEdgeAttributePresence(
                relationshipScanCursor, propertyCursor, relationTypeSchemaDescriptor, tokenNameLookup, isDependent);
    }

    @Override
    public void validateRelationshipPropertyExistenceConstraint(
            RelationshipTypeIndexCursor relationshipTypeIndexCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor relationTypeSchemaDescriptor,
            TokenNameLookup tokenNameLookup,
            boolean isDependent,
            MemoryTracker memoryTracker,
            BooleanSupplier doContinue)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEdgeAttributePresence(
                relationshipTypeIndexCursor,
                propertyCursor,
                relationTypeSchemaDescriptor,
                tokenNameLookup,
                isDependent,
                doContinue);
    }

    @Override
    public void validateNodePropertyTypeConstraint(
            FilteringNodeCursorWrapper nodeCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor typeConstraintDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditVertexTypeConformance(
                nodeCursor, propertyCursor, typeConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void validateNodePropertyTypeConstraint(
            NodeLabelIndexCursor nodeLabelIndexCursor,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor typeConstraintDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditVertexTypeConformance(
                nodeLabelIndexCursor, nodeCursor, propertyCursor, typeConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void validateRelationshipPropertyTypeConstraint(
            FilteringRelationshipScanCursorWrapper relationshipScanCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor typeConstraintDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEdgeTypeConformance(
                relationshipScanCursor, propertyCursor, typeConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void validateRelationshipPropertyTypeConstraint(
            RelationshipTypeIndexCursor relationshipTypeIndexCursor,
            PropertyCursor propertyCursor,
            TypeConstraintDescriptor typeConstraintDescriptor,
            TokenNameLookup tokenNameLookup,
            MemoryTracker memoryTracker)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEdgeTypeConformance(
                relationshipTypeIndexCursor, propertyCursor, typeConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void validateRelationshipEndpointLabelConstraint(
            RelationshipScanCursor relationshipScanCursor,
            NodeCursor nodeCursor,
            RelationshipEndpointLabelConstraintDescriptor relationshipEndpointLabelConstraintDescriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEndpointLabelPresence(
                relationshipScanCursor, nodeCursor, relationshipEndpointLabelConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void validateRelationshipEndpointLabelConstraint(
            RelationshipTypeIndexCursor relationshipTypeIndexCursor,
            NodeCursor nodeCursor,
            RelationshipEndpointLabelConstraintDescriptor relationshipEndpointLabelConstraintDescriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditEndpointLabelPresence(
                relationshipTypeIndexCursor,
                nodeCursor,
                relationshipEndpointLabelConstraintDescriptor,
                tokenNameLookup);
    }

    @Override
    public void validateNodeLabelExistenceConstraint(
            NodeLabelIndexCursor nodeLabelIndexCursor,
            NodeCursor nodeCursor,
            NodeLabelExistenceConstraintDescriptor nodeLabelExistenceConstraintDescriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditLabelCoexistence(
                nodeLabelIndexCursor, nodeCursor, nodeLabelExistenceConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public void validateNodeLabelExistenceConstraint(
            NodeCursor nodeCursor,
            NodeLabelExistenceConstraintDescriptor nodeLabelExistenceConstraintDescriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        ExistingDataAuditor.auditLabelCoexistence(nodeCursor, nodeLabelExistenceConstraintDescriptor, tokenNameLookup);
    }

    @Override
    public TxStateVisitor decorateTxStateVisitor(
            StorageReader storageReader,
            Read read,
            CursorFactory cursorFactory,
            ReadableTransactionState txState,
            TxStateVisitor txStateVisitor,
            CursorContext cursorContext,
            MemoryTracker memoryTracker) {
        return !txState.hasDataChanges()
                ? txStateVisitor
                : SchemaEnforcementProvider.obtain(storageReader)
                        .decorateVisitor(cursorContext, cursorFactory, memoryTracker, read, txStateVisitor);
    }
}
