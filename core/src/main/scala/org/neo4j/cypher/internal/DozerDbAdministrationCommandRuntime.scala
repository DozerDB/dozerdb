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

package org.neo4j.cypher.internal

import org.neo4j.common.DependencyResolver
import org.neo4j.configuration.Config
import org.neo4j.configuration.DozerDbSettings
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.configuration.helpers.DatabaseNameValidator
import org.neo4j.cypher.internal.AdministrationCommandRuntime.DatabaseNameFields
import org.neo4j.cypher.internal.AdministrationCommandRuntime.checkNamespaceExists
import org.neo4j.cypher.internal.AdministrationCommandRuntime.getDatabaseNameFields
import org.neo4j.cypher.internal.AdministrationCommandRuntime.getNameFields
import org.neo4j.cypher.internal.AdministrationCommandRuntime.internalKey
import org.neo4j.cypher.internal.AdministrationCommandRuntime.makeRenameExecutionPlan
import org.neo4j.cypher.internal.AdministrationCommandRuntime.runtimeStringValue
import org.neo4j.cypher.internal.administration.CommunityAlterDatabaseExecutionPlanner
import org.neo4j.cypher.internal.administration.DoNothingExecutionPlanner
import org.neo4j.cypher.internal.administration.DozerDbAlterUserExecutionPlanner
import org.neo4j.cypher.internal.administration.DozerDbCreateUserExecutionPlanner
import org.neo4j.cypher.internal.administration.DropUserExecutionPlanner
import org.neo4j.cypher.internal.administration.EnsureNodeExistsExecutionPlanner
import org.neo4j.cypher.internal.administration.SetOwnPasswordExecutionPlanner
import org.neo4j.cypher.internal.administration.SystemProcedureCallPlanner
import org.neo4j.cypher.internal.ast.AdministrationAction
import org.neo4j.cypher.internal.ast.DatabaseName
import org.neo4j.cypher.internal.ast.DbmsAction
import org.neo4j.cypher.internal.ast.DropDatabaseAliasAction
import org.neo4j.cypher.internal.ast.NoOptions
import org.neo4j.cypher.internal.ast.StartDatabaseAction
import org.neo4j.cypher.internal.ast.StopDatabaseAction
import org.neo4j.cypher.internal.expressions.Parameter
import org.neo4j.cypher.internal.logical.plans.AdministrationCommandLogicalPlan
import org.neo4j.cypher.internal.logical.plans.AllowedNonAdministrationCommands
import org.neo4j.cypher.internal.logical.plans.AlterDatabase
import org.neo4j.cypher.internal.logical.plans.AlterUser
import org.neo4j.cypher.internal.logical.plans.AlterUsers
import org.neo4j.cypher.internal.logical.plans.AssertAllowedDatabaseAction
import org.neo4j.cypher.internal.logical.plans.AssertAllowedDbmsActions
import org.neo4j.cypher.internal.logical.plans.AssertAllowedDbmsActionsOrSelf
import org.neo4j.cypher.internal.logical.plans.AssertCanAlterDatabase
import org.neo4j.cypher.internal.logical.plans.AssertCanDropDatabase
import org.neo4j.cypher.internal.logical.plans.AssertManagementActionNotBlocked
import org.neo4j.cypher.internal.logical.plans.AssertNotCurrentUser
import org.neo4j.cypher.internal.logical.plans.AssertNotGraphShard
import org.neo4j.cypher.internal.logical.plans.AssertNotInvalidActionOnShard
import org.neo4j.cypher.internal.logical.plans.AssertNotPropertyShard
import org.neo4j.cypher.internal.logical.plans.AssertNotShardedDatabase
import org.neo4j.cypher.internal.logical.plans.AssertNotVirtualSpd
import org.neo4j.cypher.internal.logical.plans.CheckNativeAuthentication
import org.neo4j.cypher.internal.logical.plans.CreateDatabase
import org.neo4j.cypher.internal.logical.plans.CreateUser
import org.neo4j.cypher.internal.logical.plans.DoNothingIfDatabaseExists
import org.neo4j.cypher.internal.logical.plans.DoNothingIfDatabaseNotExists
import org.neo4j.cypher.internal.logical.plans.DoNothingIfExists
import org.neo4j.cypher.internal.logical.plans.DoNothingIfNotExists
import org.neo4j.cypher.internal.logical.plans.DropDatabase
import org.neo4j.cypher.internal.logical.plans.DropUser
import org.neo4j.cypher.internal.logical.plans.EnsureDatabaseSafeToDelete
import org.neo4j.cypher.internal.logical.plans.EnsureNameIsNotAmbiguous
import org.neo4j.cypher.internal.logical.plans.EnsureNodeExists
import org.neo4j.cypher.internal.logical.plans.EnsureValidNonSystemDatabase
import org.neo4j.cypher.internal.logical.plans.EnsureValidNumberOfDatabases
import org.neo4j.cypher.internal.logical.plans.LogSystemCommand
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.NameValidator
import org.neo4j.cypher.internal.logical.plans.PrivilegePlan
import org.neo4j.cypher.internal.logical.plans.RenameUser
import org.neo4j.cypher.internal.logical.plans.SetOwnPassword
import org.neo4j.cypher.internal.logical.plans.ShowUsers
import org.neo4j.cypher.internal.logical.plans.StartDatabase
import org.neo4j.cypher.internal.logical.plans.StopDatabase
import org.neo4j.cypher.internal.logical.plans.SystemProcedureCall
import org.neo4j.cypher.internal.procs.ActionMapper
import org.neo4j.cypher.internal.procs.AuthorizationAndPredicateExecutionPlan
import org.neo4j.cypher.internal.procs.Continue
import org.neo4j.cypher.internal.procs.ParameterTransformer
import org.neo4j.cypher.internal.procs.ParameterTransformer.ParameterGenerationFunction
import org.neo4j.cypher.internal.procs.PredicateExecutionPlan
import org.neo4j.cypher.internal.procs.QueryHandler
import org.neo4j.cypher.internal.procs.SystemCommandExecutionPlan
import org.neo4j.cypher.internal.procs.ThrowException
import org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlan
import org.neo4j.cypher.rendering.QueryRenderer
import org.neo4j.dbms.api.DatabaseLimitReachedException
import org.neo4j.dbms.database.DefaultSystemGraphComponent
import org.neo4j.dbms.systemgraph.SecurityGraphDbmsModel.USER_NAME_PROPERTY
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.DATABASE_NAME
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.DATABASE_NAME_PROPERTY
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.GRAPH_SHARD
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.NAMESPACE_PROPERTY
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.PROPERTY_SHARD
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.SPD
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModelConstants.TARGETS
import org.neo4j.exceptions.CantCompileQueryException
import org.neo4j.exceptions.CypherExecutionException
import org.neo4j.exceptions.DatabaseAdministrationOnFollowerException
import org.neo4j.exceptions.InvalidArgumentException
import org.neo4j.exceptions.InvalidSemanticsException
import org.neo4j.exceptions.Neo4jException
import org.neo4j.gqlstatus.GqlHelper
import org.neo4j.gqlstatus.PrivilegeGqlCodeEntity
import org.neo4j.graphdb.security.AuthorizationViolationException
import org.neo4j.internal.kernel.api.security.AbstractSecurityLog
import org.neo4j.internal.kernel.api.security.AdminActionOnResource
import org.neo4j.internal.kernel.api.security.AdminActionOnResource.DatabaseScope
import org.neo4j.internal.kernel.api.security.PermissionState
import org.neo4j.internal.kernel.api.security.SecurityAuthorizationHandler
import org.neo4j.internal.kernel.api.security.SecurityContext
import org.neo4j.internal.kernel.api.security.SecurityExceptionLogger
import org.neo4j.internal.kernel.api.security.Segment
import org.neo4j.internal.kernel.api.security.StaticAccessMode
import org.neo4j.kernel.api.exceptions.Status
import org.neo4j.kernel.api.exceptions.Status.HasStatus
import org.neo4j.kernel.database.NamedDatabaseId
import org.neo4j.kernel.database.NormalizedDatabaseName
import org.neo4j.kernel.impl.api.security.RestrictedAccessMode
import org.neo4j.kernel.impl.query.TransactionalContext.DatabaseMode
import org.neo4j.server.security.systemgraph.ShowUsersOutput
import org.neo4j.server.security.systemgraph.UserSecurityGraphComponent
import org.neo4j.values.storable.BooleanValue
import org.neo4j.values.storable.LongValue
import org.neo4j.values.storable.TextValue
import org.neo4j.values.storable.UTF8StringValue
import org.neo4j.values.storable.Value
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.VirtualValues

import java.util.concurrent.ThreadLocalRandom

/**
 * This runtime takes on queries that work on the system database, such as multi-database and security administration commands.
 * The planning requirements for these are much simpler than normal Cypher commands, and as such the runtime stack is also different.
 *
 * Derived from Neo4j's community administration runtime. The DozerDB delta is the set of multi-database
 * commands (CREATE, DROP, START and STOP DATABASE and their guard plans) that community rejects, plus
 * the user commands that community restricts.
 */
case class DozerDbAdministrationCommandRuntime(
  normalExecutionEngine: ExecutionEngine,
  resolver: DependencyResolver,
  extraLogicalToExecutable: PartialFunction[LogicalPlan, AdministrationCommandRuntimeContext => ExecutionPlan] =
    DozerDbAdministrationCommandRuntime.emptyLogicalToExecutable
) extends AdministrationCommandRuntime {

  override def name: String = "dozerdb enhanced community administration-commands"

  private val securityLog = resolver.resolveDependency(classOf[AbstractSecurityLog])

  private lazy val securityAuthorizationHandler =
    new SecurityAuthorizationHandler(securityLog)

  private val config: Config = resolver.resolveDependency(classOf[Config])

  private val maximumGdbsAllowed: Integer = config.get(DozerDbSettings.max_databases)

  private lazy val userSecurity: UserSecurityGraphComponent =
    resolver.resolveDependency(classOf[UserSecurityGraphComponent])

  def throwCantCompile(unknownPlan: LogicalPlan): Nothing = {
    throw CantCompileQueryException.planNotRecognisedInAdminCommand(unknownPlan.getClass.getSimpleName)
  }

  override def compileToExecutable(
    state: LogicalQuery,
    context: RuntimeContext,
    databaseMode: DatabaseMode
  ): ExecutionPlan = {
    // Either the logical plan is a command that the partial function logicalToExecutable provides/understands OR we throw an error
    logicalToExecutable.applyOrElse(state.logicalPlan, throwCantCompile).apply(
      AdministrationCommandRuntimeContext(context)
    )
  }

  // When the community commands are run within enterprise, this allows the enterprise commands to be chained
  private def fullLogicalToExecutable = extraLogicalToExecutable orElse logicalToExecutable

  val checkShowUserPrivilegesText: String =
    "Try executing SHOW USER PRIVILEGES to determine the missing or denied privileges. " +
      "In case of missing privileges, they need to be granted (See GRANT). In case of denied privileges, they need to be revoked (See REVOKE) and granted."

  def prettifyActionName(actions: AdministrationAction*): String = {
    actions.map {
      case StartDatabaseAction => "START DATABASE"
      case StopDatabaseAction  => "STOP DATABASE"
      case a                   => a.name
    }.distinct.sorted.mkString(" and/or ")
  }

  private[internal] def adminActionErrorMessage(
    permissionState: PermissionState,
    actions: Seq[AdministrationAction]
  ): String = {
    permissionState match {
      case PermissionState.EXPLICIT_DENY =>
        s"Permission denied for ${prettifyActionName(actions: _*)}. $checkShowUserPrivilegesText"
      case PermissionState.NOT_GRANTED =>
        s"Permission has not been granted for ${prettifyActionName(actions: _*)}. $checkShowUserPrivilegesText"
      case PermissionState.EXPLICIT_GRANT => ""
    }
  }

  private def getSource(
    maybeSource: Option[PrivilegePlan],
    context: AdministrationCommandRuntimeContext
  ): Option[ExecutionPlan] =
    maybeSource match {
      case Some(source) => Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
      case _            => None
    }

  private[internal] def checkActions(
    actions: Seq[DbmsAction],
    securityContext: SecurityContext
  ): Seq[(DbmsAction, PermissionState)] =
    actions.map { action =>
      (
        action,
        securityContext.allowsAdminAction(new AdminActionOnResource(
          ActionMapper.asKernelAction(action),
          DatabaseScope.ALL,
          Segment.ALL
        ))
      )
    }

  private def checkAdminRightsForDBMSOrSelf(
    user: Either[String, Parameter],
    actions: Seq[DbmsAction]
  ): AdministrationCommandRuntimeContext => ExecutionPlan = _ => {
    AuthorizationAndPredicateExecutionPlan(
      securityLog,
      (params, securityContext) => {
        if (securityContext.subject().hasUsername(runtimeStringValue(user, params)))
          Seq((null, PermissionState.EXPLICIT_GRANT))
        else checkActions(actions, securityContext)
      },
      violationMessage = adminActionErrorMessage
    )
  }

  def logicalToExecutable: PartialFunction[LogicalPlan, AdministrationCommandRuntimeContext => ExecutionPlan] = {
    // Check Admin Rights for DBMS commands
    case AssertAllowedDbmsActions(maybeSource, actions) => context =>
        AuthorizationAndPredicateExecutionPlan(
          securityLog,
          (_, securityContext) => checkActions(actions, securityContext),
          violationMessage = adminActionErrorMessage,
          source = getSource(maybeSource, context)
        )

    // Check Admin Rights for DBMS commands or self
    case AssertAllowedDbmsActionsOrSelf(user, actions) =>
      context => checkAdminRightsForDBMSOrSelf(user, actions)(context)

    // Check rights for ALTER DATABASE, does the same as AssertAllowedDbmsActions
    // using the non-composite privileges, since DozerDB doesn't have composite databases
    case AssertCanAlterDatabase(source, database, _, actions) => context =>
        AuthorizationAndPredicateExecutionPlan(
          securityLog,
          (params, securityContext) =>
            actions.map(action =>
              (
                action,
                securityContext.allowsAdminAction(
                  new AdminActionOnResource(
                    ActionMapper.asKernelAction(action),
                    new DatabaseScope(runtimeStringValue(database, params)),
                    Segment.ALL
                  )
                )
              )
            ),
          violationMessage = adminActionErrorMessage,
          source = getSource(Some(source), context)
        )

    // Check that the specified user is not the logged in user (eg. for some CREATE/DROP/ALTER USER commands)
    case AssertNotCurrentUser(source, userName, verb, violationMessage, errorGqlStatusObject) => context =>
        PredicateExecutionPlan(
          (params, sc) => !sc.subject().hasUsername(runtimeStringValue(userName, params)),
          onViolation = (_, _, sc) =>
            new InvalidArgumentException(
              errorGqlStatusObject,
              s"Failed to $verb the specified user '${sc.subject().executingUser()}': $violationMessage."
            ),
          source = Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )

    // Check Admin Rights for some Database commands
    case AssertAllowedDatabaseAction(action, database, maybeSource) => context =>
        AuthorizationAndPredicateExecutionPlan(
          securityLog,
          (params, securityContext) =>
            Seq((
              action,
              securityContext.allowsAdminAction(
                new AdminActionOnResource(
                  ActionMapper.asKernelAction(action),
                  new DatabaseScope(runtimeStringValue(database, params)),
                  Segment.ALL
                )
              )
            )),
          violationMessage = adminActionErrorMessage,
          source = getSource(maybeSource, context)
        )

    // SHOW USERS
    case ShowUsers(source, withAuth, asCommands, symbols, yields, returns) => context => {
        val rowsKey = internalKey(ShowUsersOutput.USERS)

        val genFunc: ParameterGenerationFunction = (tx, securityContext, params) => {
          val outputParams = userSecurity.showUsers(tx, withAuth, true, asCommands, false)
          outputParams.toMapValue
        }

        val returnClause =
          AdministrationShowCommandUtils.generateReturnClause(
            symbols,
            yields,
            returns,
            Seq("user")
          )
        SystemCommandExecutionPlan(
          "ShowUsers",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             |UNWIND $$$rowsKey AS row
             |  WITH
             |  row.${ShowUsersOutput.COMMAND} AS command,
             |  row.${ShowUsersOutput.USER} AS user,
             |  null AS roles,
             |  row.${ShowUsersOutput.PASSWORD_CHANGE_REQ} AS passwordChangeRequired,
             |  null AS suspended,
             |  null AS home,
             |  row.${ShowUsersOutput.PROVIDER} AS provider,
             |  row.${ShowUsersOutput.AUTH} AS auth,
             |  row.${ShowUsersOutput.TAGS} AS tags
             |$returnClause
             |""".stripMargin,
          VirtualValues.EMPTY_MAP,
          source = Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)),
          parameterTransformer = ParameterTransformer(genFunc),
          cypherVersion = context.runtimeContext.cypherVersion
        )
      }

    // CREATE [OR REPLACE] USER foo [IF NOT EXISTS] SET [PLAINTEXT | ENCRYPTED] PASSWORD 'password'
    // CREATE [OR REPLACE] USER foo [IF NOT EXISTS] SET [PLAINTEXT | ENCRYPTED] PASSWORD $password
    case createUser: CreateUser => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(createUser.source, throwCantCompile).apply(context))
        DozerDbCreateUserExecutionPlanner(
          normalExecutionEngine,
          securityAuthorizationHandler,
          config
        ).planCreateUser(
          createUser,
          sourcePlan
        )

    // RENAME USER
    case RenameUser(source, fromUserName, toUserName) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        makeRenameExecutionPlan(
          PrivilegeGqlCodeEntity.USER,
          USER_NAME_PROPERTY,
          fromUserName,
          toUserName,
          params => {
            val toName = runtimeStringValue(toUserName, params)
            NameValidator.assertValidUsername(toName)
          }
        )(sourcePlan, normalExecutionEngine, securityAuthorizationHandler)

    // ALTER USERS is a tag-only command; DozerDB does not support user tags
    case _: AlterUsers => _ =>
        throw CantCompileQueryException.commandUnsupportedInCommunityEdition("ALTER USERS")

    // ALTER USER foo [SET [PLAINTEXT | ENCRYPTED] PASSWORD pw] [CHANGE [NOT] REQUIRED]
    case alterUser: AlterUser => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(alterUser.source, throwCantCompile).apply(context))
        DozerDbAlterUserExecutionPlanner(
          normalExecutionEngine,
          securityAuthorizationHandler,
          userSecurity,
          config
        ).planAlterUser(
          alterUser,
          sourcePlan
        )

    // DROP USER foo [IF EXISTS]
    case DropUser(source, userName) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DropUserExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDropUser(userName, sourcePlan)

    // ALTER CURRENT USER SET PASSWORD FROM 'currentPassword' TO 'newPassword'
    // ALTER CURRENT USER SET PASSWORD FROM 'currentPassword' TO $newPassword
    // ALTER CURRENT USER SET PASSWORD FROM $currentPassword TO 'newPassword'
    // ALTER CURRENT USER SET PASSWORD FROM $currentPassword TO $newPassword
    case SetOwnPassword(source, newPassword, currentPassword) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        SetOwnPasswordExecutionPlanner(
          normalExecutionEngine,
          securityAuthorizationHandler,
          config,
          securityLog
        ).planSetOwnPassword(
          newPassword,
          currentPassword,
          sourcePlan
        )

    // ALTER DATABASE SET DEFAULT LANGUAGE
    case AlterDatabase(source, databaseName, None, None, NoOptions, Some(defaultLanguageVersion), optionsToRemove, None)
      if optionsToRemove.isEmpty =>
      context => {
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        CommunityAlterDatabaseExecutionPlanner(
          normalExecutionEngine,
          securityAuthorizationHandler
        ).planAlterDatabase(databaseName, defaultLanguageVersion, sourcePlan, context)
      }

    case AssertNotVirtualSpd(source, databaseName, action, actionVerb) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        val nameFields = getDatabaseNameFields("databaseName", databaseName)

        val parameterTransformer = ParameterTransformer()
          .convert(nameFields.nameConverter)
          .validate(checkNamespaceExists(nameFields, context))

        UpdatingSystemCommandExecutionPlan(
          "AssertNotSpd",
          normalExecutionEngine,
          securityAuthorizationHandler,
          query =
            s"""
               |MATCH (n:$DATABASE_NAME)-[:$TARGETS]->(d:$SPD)
               |  WHERE n.$DATABASE_NAME_PROPERTY = $$`${nameFields.nameKey}`
               |  AND n.$NAMESPACE_PROPERTY = $$`${nameFields.namespaceKey}`
               |RETURN d.$DATABASE_NAME_PROPERTY AS dbName""".stripMargin,
          VirtualValues.map(
            Array(nameFields.nameKey, nameFields.namespaceKey),
            Array(nameFields.nameValue, nameFields.namespaceValue)
          ),
          queryHandler = QueryHandler.handleResult((_, _, _) =>
            ThrowException(
              InvalidSemanticsException.invalidAlterShardedTarget(
                action
              )
            )
          ).handleError((error, params) =>
            (error, error.getCause) match {
              case (e: HasStatus, _) if e.status() == Status.Cluster.NotALeader =>
                DatabaseAdministrationOnFollowerException.notALeader(
                  action,
                  s"Failed to $actionVerb the specified database '${runtimeStringValue(databaseName, params)}'",
                  error
                )
              case _ => error
            }
          ),
          source = sourcePlan,
          parameterTransformer = parameterTransformer
        )

    case AssertNotGraphShard(source, databaseName, action, actionVerb) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        val nameFields = getDatabaseNameFields("databaseName", databaseName)

        val parameterTransformer = ParameterTransformer()
          .convert(nameFields.nameConverter)
          .validate(checkNamespaceExists(nameFields, context))

        UpdatingSystemCommandExecutionPlan(
          "AssertNotGraphShard",
          normalExecutionEngine,
          securityAuthorizationHandler,
          query =
            s"""
               |MATCH (n:$DATABASE_NAME)-[:$TARGETS]->(d:$GRAPH_SHARD)
               |  WHERE n.$DATABASE_NAME_PROPERTY = $$`${nameFields.nameKey}`
               |  AND n.$NAMESPACE_PROPERTY = $$`${nameFields.namespaceKey}`
               |RETURN d.$DATABASE_NAME_PROPERTY AS dbName""".stripMargin,
          VirtualValues.map(
            Array(nameFields.nameKey, nameFields.namespaceKey),
            Array(nameFields.nameValue, nameFields.namespaceValue)
          ),
          queryHandler = QueryHandler.handleResult((_, _, _) =>
            ThrowException(
              InvalidSemanticsException.invalidAlterGraphShardTarget(
                action
              )
            )
          ).handleError((error, params) =>
            (error, error.getCause) match {
              case (e: HasStatus, _) if e.status() == Status.Cluster.NotALeader =>
                DatabaseAdministrationOnFollowerException.notALeader(
                  action,
                  s"Failed to $actionVerb the specified database '${runtimeStringValue(databaseName, params)}'",
                  error
                )
              case _ => error
            }
          ),
          source = sourcePlan,
          parameterTransformer = parameterTransformer
        )

    case AssertNotPropertyShard(source, databaseName, action, actionVerb) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        val nameFields = getDatabaseNameFields("databaseName", databaseName)

        val parameterTransformer = ParameterTransformer()
          .convert(nameFields.nameConverter)
          .validate(checkNamespaceExists(nameFields, context))

        UpdatingSystemCommandExecutionPlan(
          "AssertNotPropertyShard",
          normalExecutionEngine,
          securityAuthorizationHandler,
          query =
            s"""
               |MATCH (n:$DATABASE_NAME)-[:$TARGETS]->(d:$PROPERTY_SHARD)
               |  WHERE n.$DATABASE_NAME_PROPERTY = $$`${nameFields.nameKey}`
               |  AND n.$NAMESPACE_PROPERTY = $$`${nameFields.namespaceKey}`
               |RETURN d.$DATABASE_NAME_PROPERTY AS dbName""".stripMargin,
          VirtualValues.map(
            Array(nameFields.nameKey, nameFields.namespaceKey),
            Array(nameFields.nameValue, nameFields.namespaceValue)
          ),
          queryHandler = QueryHandler.handleResult((_, _, _) =>
            ThrowException(
              InvalidSemanticsException.invalidAlterShardTarget(
                action
              )
            )
          ).handleError((error, params) =>
            (error, error.getCause) match {
              case (e: HasStatus, _) if e.status() == Status.Cluster.NotALeader =>
                DatabaseAdministrationOnFollowerException.notALeader(
                  action,
                  s"Failed to $actionVerb the specified database '${runtimeStringValue(databaseName, params)}'",
                  error
                )
              case _ => error
            }
          ),
          source = sourcePlan,
          parameterTransformer = parameterTransformer
        )

    // DozerDB does not support sharded databases, so these shard assertions pass through to their source.
    case AssertNotInvalidActionOnShard(source, _, _, _) =>
      context => fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)

    case AssertNotShardedDatabase(source, _, _, _) =>
      context => fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)

    // STOP DATABASE foo: DozerDB marks the database offline; the system graph listener stops it.
    case StopDatabase(source: AdministrationCommandLogicalPlan, databaseName: DatabaseName) => context => {
        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val nameValue: Value = nameFields.nameValue
        UpdatingSystemCommandExecutionPlan(
          "StopDatabase",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | MATCH (database:Database {name: $$name})
             | SET database.status = '${DatabaseStatus.Offline.stringValue()}',
             |     database.stopped_at = datetime(),
             |     database.started_at = null,
             |     database.updated_at = datetime()
             | RETURN database.name as name, database.currentStatus as status
    """.stripMargin,
          VirtualValues.map(
            Array("name"),
            Array(nameValue)
          ),
          QueryHandler
            .handleError {
              case (error, _) =>
                new IllegalStateException(
                  s"Could not STOP the database called ${nameValue}.",
                  error
                )
            },
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )
      }

    // START DATABASE foo: DozerDB marks the database online; the system graph listener starts it.
    case StartDatabase(source: AdministrationCommandLogicalPlan, databaseName: DatabaseName) => context => {
        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val nameValue: Value = nameFields.nameValue
        UpdatingSystemCommandExecutionPlan(
          "StartDatabase",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | MATCH (database:Database {name: $$name})
             | SET database.status = '${DatabaseStatus.Online.stringValue()}',
             |     database.stopped_at = null,
             |     database.started_at = datetime(),
             |     database.updated_at = datetime()
             | RETURN database.name as name, database.currentStatus as status
    """.stripMargin,
          VirtualValues.map(
            Array("name"),
            Array(nameValue)
          ),
          QueryHandler
            .handleError {
              case (error, _) =>
                new IllegalStateException(
                  s"Could not START the database called ${nameValue}.",
                  error
                )
            },
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )
      }

    // DROP DATABASE foo: remove the database nodes and record the deletion for the system graph listener.
    case DropDatabase(source, databaseName, _, _, _) => context => {
        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val parameterTransformer = ParameterTransformer()
          .convert(nameFields.nameConverter)

        UpdatingSystemCommandExecutionPlan(
          "DropDatabase",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | MATCH (database:Database {name: $$`${nameFields.nameKey}`})
             | OPTIONAL MATCH (database)-[r]-()
             | OPTIONAL MATCH (databaseName:DatabaseName {name: $$`${nameFields.nameKey}`})
             | OPTIONAL MATCH (databaseName)-[r2]-()
             | DELETE r, database, r2, databaseName
             | WITH $$`${nameFields.nameKey}` as droppedName
             | CREATE (deletedDatabase:DeletedDatabase {name: droppedName, timestamp: timestamp()})
             | RETURN droppedName
    """.stripMargin,
          VirtualValues.map(
            Array(nameFields.nameKey),
            Array(nameFields.nameValue)
          ),
          QueryHandler
            .handleError {
              case (error, _) =>
                new IllegalStateException(
                  s"Could not drop the database called ${nameFields.nameValue}.",
                  error
                )
            },
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)),
          parameterTransformer = parameterTransformer
        )
      }

    // CREATE DATABASE foo: validate the name and create the database nodes; the system graph listener
    // creates and starts the database after commit.
    case CreateDatabase(source, databaseName, _, _, _, _, _) => context => {
        val defaultLanguage: String = DefaultSystemGraphComponent
          .cypherVersionFromConfig(config.get(GraphDatabaseSettings.default_language))
          .persistedValue

        val nameFields = getNameFields(
          "databaseName",
          databaseName,
          valueMapper = nameString => {
            val normalizedDatabaseName: NormalizedDatabaseName = new NormalizedDatabaseName(nameString)
            try {
              DatabaseNameValidator.validateExternalDatabaseName(normalizedDatabaseName)
            } catch {
              case exception: IllegalArgumentException =>
                throw new InvalidArgumentException(
                  GqlHelper.get50N00(classOf[InvalidArgumentException].getSimpleName, exception.getMessage),
                  exception.getMessage
                )
            }
            normalizedDatabaseName.name
          }
        )

        val parameterTransformer = ParameterTransformer()
          .convert(nameFields.nameConverter)

        UpdatingSystemCommandExecutionPlan(
          "CreateDatabase",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | CREATE (database:Database)<-[:TARGETS]-(:DatabaseName {displayName: $$`${nameFields.nameKey}`,
             | name: $$`${nameFields.nameKey}`, namespace: 'system-root', primary:true})
             | SET
             | database.access = 'READ_WRITE',
             | database.created_at = datetime(),
             | database.started_at = datetime(),
             | database.defaultLanguage = $$defaultLanguage,
             | database.store_random_id = $$storeRandomId,
             | database.name = $$`${nameFields.nameKey}`,
             | database.status = $$status,
             | database.uuid = randomUUID()
             | RETURN database.name as name, database.status as status, database.uuid as uuid
        """.stripMargin,
          VirtualValues.map(
            Array(nameFields.nameKey, "defaultLanguage", "storeRandomId", "status"),
            Array(
              nameFields.nameValue,
              Values.utf8Value(defaultLanguage),
              Values.longValue(ThreadLocalRandom.current().nextLong()),
              Values.stringValue(DatabaseStatus.Online.stringValue())
            )
          ),
          QueryHandler
            .handleError {
              case (error, params) =>
                new IllegalStateException(
                  s"Could not create new gdb called '${runtimeStringValue(databaseName, params)}'. It most likely already exists.",
                  error
                )
            },
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)),
          parameterTransformer = parameterTransformer
        )
      }

    // DozerDB checks the management privileges that community would otherwise skip.
    case AssertManagementActionNotBlocked(_, actions) => _ =>
        AuthorizationAndPredicateExecutionPlan(
          securityLog,
          (_, securityContext) =>
            actions.map(action =>
              (
                action,
                securityContext.allowsAdminAction(
                  new AdminActionOnResource(
                    ActionMapper.asKernelAction(action),
                    new DatabaseScope(""),
                    Segment.ALL
                  )
                )
              )
            ),
          violationMessage = adminActionErrorMessage
        )

    case EnsureNameIsNotAmbiguous(source, _, _) =>
      context => fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)

    case AssertCanDropDatabase(
        source: PrivilegePlan,
        databaseName: DatabaseName,
        action: DbmsAction
      ) => context =>
        AuthorizationAndPredicateExecutionPlan(
          securityLog,
          (params, securityContext) =>
            Seq((
              action,
              securityContext.allowsAdminAction(
                new AdminActionOnResource(
                  ActionMapper.asKernelAction(action),
                  new DatabaseScope(runtimeStringValue(databaseName, params)),
                  Segment.ALL
                )
              )
            )),
          violationMessage = adminActionErrorMessage,
          source = getSource(Some(source), context)
        )

    case EnsureDatabaseSafeToDelete(
        source: AdministrationCommandLogicalPlan,
        databaseName: DatabaseName,
        _: DropDatabaseAliasAction,
        _
      ) => context =>
        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val nameValue: Value = nameFields.nameValue

        UpdatingSystemCommandExecutionPlan(
          "EnsureDatabaseSafeToDelete",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | MATCH (database:Database {name: $$name}) RETURN database.name as name
        """.stripMargin,
          VirtualValues.map(
            Array("name"),
            Array(nameValue)
          ),
          QueryHandler
            .handleNoResult(params =>
              Some(ThrowException(
                CypherExecutionException.internalError(
                  "EnsureDatabaseSafeToDelete",
                  s"Database not found:  '${runtimeStringValue(databaseName, params)}'."
                )
              ))
            )
            .handleResult((_, _, _) => Continue),
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )

    case EnsureValidNonSystemDatabase(source, _, databaseName, _, _) => context =>
        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val nameValue: Value = nameFields.nameValue

        UpdatingSystemCommandExecutionPlan(
          "EnsureValidNonSystemDatabase",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | MATCH (database:Database {name: $$name}) RETURN database.name as name
        """.stripMargin,
          VirtualValues.map(
            Array("name"),
            Array(nameValue)
          ),
          QueryHandler
            .handleNoResult(params =>
              Some(ThrowException(
                CypherExecutionException.internalError(
                  "EnsureValidNonSystemDatabase",
                  s"Database not found:  '${runtimeStringValue(databaseName, params)}'."
                )
              ))
            )
            .handleResult((_, name, _) => {
              val nameString = name.asInstanceOf[UTF8StringValue].stringValue()
              // If the nameString is the system database then throw an error
              if (NamedDatabaseId.SYSTEM_DATABASE_NAME.equalsIgnoreCase(nameString)) {
                throw CypherExecutionException.internalError(
                  "EnsureValidNonSystemDatabase",
                  "You can not delete the system database."
                )
              }
              Continue
            }),
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )

    case EnsureValidNumberOfDatabases(source) => context =>
        UpdatingSystemCommandExecutionPlan(
          "EnsureValidNumberOfDatabases",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"""
             | MATCH (database:Database) RETURN count(database) as gdbCount
        """.stripMargin,
          VirtualValues.EMPTY_MAP,
          QueryHandler
            .handleResult((_, gdbCount, _) => {
              val gdbCountLong: Long = gdbCount.asInstanceOf[LongValue].longValue()
              if (gdbCountLong >= maximumGdbsAllowed) {
                val limitMessage = "You have reached the limit on the number of databases you can create."
                throw new DatabaseLimitReachedException(
                  GqlHelper.get50N00(classOf[DatabaseLimitReachedException].getSimpleName, limitMessage),
                  limitMessage
                )
              }
              Continue
            }),
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )

    case DoNothingIfNotExists(source, command, entity, name, operation, valueMapper) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfNotExists(
          command,
          entity,
          name,
          valueMapper,
          operation,
          sourcePlan
        )

    case DoNothingIfExists(source, command, entity, name, valueMapper) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfExists(
          command,
          entity,
          name,
          valueMapper,
          sourcePlan
        )

    case DoNothingIfDatabaseNotExists(source, command, name, operation, databaseTypeFilter, false) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfDatabaseNotExists(
          command,
          name,
          operation,
          sourcePlan,
          databaseTypeFilter,
          context
        )

    case DoNothingIfDatabaseNotExists(source, command, name, operation, databaseTypeFilter, true) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(
          normalExecutionEngine,
          securityAuthorizationHandler
        ).planDoNothingIfDatabaseNotExistsUpdateContext(
          command,
          name,
          operation,
          sourcePlan,
          databaseTypeFilter,
          context
        )

    case DoNothingIfDatabaseExists(source, command, name, databaseTypeFilter) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfDatabaseExists(
          command,
          name,
          sourcePlan,
          databaseTypeFilter,
          context
        )

    // Ensure that the role or user exists before being dropped
    case EnsureNodeExists(source, command, entity, name, valueMapper, extraFilter, labelDescription, action) =>
      context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        EnsureNodeExistsExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler)
          .planEnsureNodeExists(command, entity, name, valueMapper, extraFilter, labelDescription, action, sourcePlan)

    // SUPPORT PROCEDURES (need to be cleared before here)
    case SystemProcedureCall(call, returns, _, checkCredentialsExpired) => context =>
        SystemProcedureCallPlanner(normalExecutionEngine, securityAuthorizationHandler).planSystemProcedureCall(
          context.runtimeContext.cypherVersion,
          call,
          returns,
          checkCredentialsExpired
        )

    case CheckNativeAuthentication(source) => context =>
        val usernameKey = internalKey("username")
        val nativeAuth = internalKey("nativelyAuthenticated")

        def currentUser(p: MapValue): String = p.get(usernameKey).asInstanceOf[TextValue].stringValue()

        val sourcePlan: Option[ExecutionPlan] =
          source.map(fullLogicalToExecutable.applyOrElse(_, throwCantCompile).apply(context))

        UpdatingSystemCommandExecutionPlan(
          "CheckNativeAuthentication",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"RETURN $$`$nativeAuth` AS nativelyAuthenticated",
          MapValue.EMPTY,
          QueryHandler
            .handleError {
              case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
                new SecurityExceptionLogger(securityLog).logAndGet(
                  DatabaseAdministrationOnFollowerException.notALeader(
                    "ALTER CURRENT USER SET PASSWORD",
                    s"User '${currentUser(p)}' failed to alter their own password",
                    error
                  )
                )
              case (error: Neo4jException, _) => error
              case (error, p) =>
                new SecurityExceptionLogger(securityLog).logAndGet(
                  CypherExecutionException.alterOwnPassword(currentUser(p), error)
                )
            }
            .handleResult((_, value, _) => {
              if (value eq BooleanValue.TRUE) Continue
              else ThrowException(AuthorizationViolationException.alterCurrentUserNotAllowed())
            }),
          parameterTransformer = ParameterTransformer((_, securityContext, _) =>
            VirtualValues.map(
              Array(nativeAuth, usernameKey),
              Array(
                Values.booleanValue(securityContext.nativelyAuthenticated()),
                Values.utf8Value(securityContext.subject().executingUser())
              )
            )
          ),
          source = sourcePlan,
          checkCredentialsExpired = false
        )

    // Non-administration commands that are allowed on system database, e.g. SHOW PROCEDURES
    case AllowedNonAdministrationCommands(statement, _) => context =>
        SystemCommandExecutionPlan(
          "AllowedNonAdministrationCommand",
          normalExecutionEngine,
          securityAuthorizationHandler,
          QueryRenderer.render(statement),
          MapValue.EMPTY,
          // If we have a non admin command executing in the system database, forbid it to make reads / writes
          // from the system graph. This is to prevent queries such as SHOW PROCEDURES YIELD * RETURN ()--()
          // from leaking nodes from the system graph: the ()--() would return empty results
          modeConverter = s => s.withMode(new RestrictedAccessMode(s.mode(), StaticAccessMode.ACCESS)),
          // While running against system will override most pre-parser options.
          // However, we shouldn't override the Cypher version,
          // so let's pass through the relevant Cypher version.
          cypherVersion = context.runtimeContext.cypherVersion
        )

    // Ignore the log command in community
    case LogSystemCommand(source, _) => context =>
        fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)
  }

  override def isApplicableAdministrationCommand(logicalPlanArg: LogicalPlan): Boolean = {
    val logicalPlan = logicalPlanArg match {
      // Ignore the log command in community
      case LogSystemCommand(source, _) => source
      case plan                        => plan
    }
    logicalToExecutable.isDefinedAt(logicalPlan)
  }
}

object DozerDbAdministrationCommandRuntime {

  def emptyLogicalToExecutable: PartialFunction[LogicalPlan, AdministrationCommandRuntimeContext => ExecutionPlan] =
    new PartialFunction[LogicalPlan, AdministrationCommandRuntimeContext => ExecutionPlan] {
      override def isDefinedAt(x: LogicalPlan): Boolean = false

      override def apply(v1: LogicalPlan): AdministrationCommandRuntimeContext => ExecutionPlan = ???
    }
}
