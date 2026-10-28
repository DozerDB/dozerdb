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
import org.neo4j.cypher.internal.AdministrationCommandRuntime.makeAlterUserExecutionPlan
import org.neo4j.cypher.internal.ExecutionEngine
import org.neo4j.cypher.internal.ExecutionPlan
import org.neo4j.cypher.internal.ast.RemoveAuth
import org.neo4j.cypher.internal.logical.plans.AlterUser
import org.neo4j.internal.kernel.api.security.SecurityAuthorizationHandler
import org.neo4j.server.security.systemgraph.UserSecurityGraphComponent

/**
 * A case class designed for planning the alteration of user properties within the Neo4j database.
 * It encapsulates the required components, such as the execution engine for running Cypher queries,
 * a security authorization handler for security and authorization processes, and the configuration settings
 * of the database.
 *
 * @param executionEngine The core engine responsible for executing Cypher queries against the database.
 * @param securityAuthorizationHandler Manages security aspects, including permissions and user authentication.
 * @param config Database configuration settings, potentially affecting the behavior of the alteration process.
 */
case class DozerDbAlterUserExecutionPlanner(
  executionEngine: ExecutionEngine,
  securityAuthorizationHandler: SecurityAuthorizationHandler,
  userSecurityGraphComponent: UserSecurityGraphComponent,
  config: Config
) {

  /**
   * Generates an execution plan for altering a user within the Neo4j database.
   * This involves modifications to user properties such as passwords, suspension status, and default database.
   *
   * @param alterUser The details of the user alteration request, encapsulating all potential changes to the user.
   * @param sourceExecutionPlan An optional existing execution plan that might influence the creation of this new plan.
   * @return An execution plan that, when executed, will apply the requested alterations to the specified user.
   *
   *  We are using the function name and api contract similar to that in Neo4j core to make it easier to compare to.
   */
  def planAlterUser(alterUser: AlterUser, sourceExecutionPlan: Option[ExecutionPlan]): ExecutionPlan = {
    makeAlterUserExecutionPlan(
      alterUser.userName,
      alterUser.suspended,
      alterUser.defaultDatabase,
      nativeAuth = alterUser.nativeAuth,
      externalAuths = Seq.empty,
      removeAuths = RemoveAuth(all = false, List.empty)
    )(sourceExecutionPlan, executionEngine, securityAuthorizationHandler, userSecurityGraphComponent, config)

  }

}
