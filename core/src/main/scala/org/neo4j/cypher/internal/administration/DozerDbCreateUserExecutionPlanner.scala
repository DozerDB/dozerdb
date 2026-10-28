/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/*
 *  Modifications Copyright (c) DozerDB
 *  https://dozerdb.org
 */
package org.neo4j.cypher.internal.administration

import org.neo4j.configuration.Config
import org.neo4j.cypher.internal.AdministrationCommandRuntime.makeCreateUserExecutionPlan
import org.neo4j.cypher.internal.ExecutionEngine
import org.neo4j.cypher.internal.ExecutionPlan
import org.neo4j.cypher.internal.ast.RemoveAuth
import org.neo4j.cypher.internal.logical.plans.CreateUser
import org.neo4j.internal.kernel.api.security.SecurityAuthorizationHandler
import org.neo4j.server.security.systemgraph.UserSecurityGraphComponent

// A case class designed for creating user execution plans within the Neo4j database.
// This class is a part of the administration tools that allow for detailed user management.
// It encapsulates the necessary components to generate execution plans for creating users.

case class DozerDbCreateUserExecutionPlanner(
  executionEngine: ExecutionEngine,
  securityAuthorizationHandler: SecurityAuthorizationHandler,
  config: Config
) {

  // This function generates an execution plan for creating a new user in the Neo4j database.
  // It takes in a CreateUser logical plan and an optional source plan, returning a finalized ExecutionPlan.
  // The creation includes setting up initial passwords, requiring password changes, and handling suspended states.
  // We use the same function name that Neo4j uses in core to make it easier to compare to.
  def planCreateUser(createUser: CreateUser, sourceExecutionPlan: Option[ExecutionPlan]): ExecutionPlan = {

    makeCreateUserExecutionPlan(
      createUser.userName,
      suspended = createUser.suspended.getOrElse(false),
      createUser.defaultDatabase,
      nativeAuth = createUser.nativeAuth,
      externalAuths = Seq.empty
    )(sourceExecutionPlan, executionEngine, securityAuthorizationHandler, config)

  }

}
