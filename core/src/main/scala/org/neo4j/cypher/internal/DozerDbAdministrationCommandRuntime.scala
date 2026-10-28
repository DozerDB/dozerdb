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
import org.neo4j.cypher.internal.AdministrationCommandRuntime.getDatabaseNameFields
import org.neo4j.cypher.internal.AdministrationCommandRuntime.getNameFields
import org.neo4j.cypher.internal.AdministrationCommandRuntime.internalKey
import org.neo4j.cypher.internal.AdministrationCommandRuntime.makeRenameExecutionPlan
import org.neo4j.cypher.internal.AdministrationCommandRuntime.runtimeStringValue
import org.neo4j.cypher.internal.AdministrationCommandRuntime.userNamePropKey
import org.neo4j.cypher.internal.administration.DoNothingExecutionPlanner
import org.neo4j.cypher.internal.administration.DozerDbAlterUserExecutionPlanner
import org.neo4j.cypher.internal.administration.DozerDbCreateUserExecutionPlanner
import org.neo4j.cypher.internal.administration.DropUserExecutionPlanner
import org.neo4j.cypher.internal.administration.EnsureNodeExistsExecutionPlanner
import org.neo4j.cypher.internal.administration.SetOwnPasswordExecutionPlanner
import org.neo4j.cypher.internal.administration.ShowDatabasesExecutionPlanner
import org.neo4j.cypher.internal.administration.ShowUsersExecutionPlanner
import org.neo4j.cypher.internal.administration.SystemProcedureCallPlanner
import org.neo4j.cypher.internal.ast.AdministrationAction
import org.neo4j.cypher.internal.ast.DatabaseName
import org.neo4j.cypher.internal.ast.DbmsAction
import org.neo4j.cypher.internal.ast.DropDatabaseAliasAction
import org.neo4j.cypher.internal.ast.StartDatabaseAction
import org.neo4j.cypher.internal.ast.StopDatabaseAction
import org.neo4j.cypher.internal.ast.UnassignableAction
import org.neo4j.cypher.internal.expressions.Parameter
import org.neo4j.cypher.internal.logical.plans.AdministrationCommandLogicalPlan
import org.neo4j.cypher.internal.logical.plans.AllowedNonAdministrationCommands
import org.neo4j.cypher.internal.logical.plans.AlterUser
import org.neo4j.cypher.internal.logical.plans.AssertAllowedDatabaseAction
import org.neo4j.cypher.internal.logical.plans.AssertAllowedDbmsActions
import org.neo4j.cypher.internal.logical.plans.AssertAllowedDbmsActionsOrSelf
import org.neo4j.cypher.internal.logical.plans.AssertCanDropDatabase
import org.neo4j.cypher.internal.logical.plans.AssertManagementActionNotBlocked
import org.neo4j.cypher.internal.logical.plans.AssertNotCurrentUser
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
import org.neo4j.cypher.internal.logical.plans.ShowCurrentUser
import org.neo4j.cypher.internal.logical.plans.ShowDatabase
import org.neo4j.cypher.internal.logical.plans.ShowUsers
import org.neo4j.cypher.internal.logical.plans.StartDatabase
import org.neo4j.cypher.internal.logical.plans.StopDatabase
import org.neo4j.cypher.internal.logical.plans.SystemProcedureCall
import org.neo4j.cypher.internal.procs.ActionMapper
import org.neo4j.cypher.internal.procs.AuthorizationAndPredicateExecutionPlan
import org.neo4j.cypher.internal.procs.Continue
import org.neo4j.cypher.internal.procs.ParameterTransformer
import org.neo4j.cypher.internal.procs.PredicateExecutionPlan
import org.neo4j.cypher.internal.procs.QueryHandler
import org.neo4j.cypher.internal.procs.SystemCommandExecutionPlan
import org.neo4j.cypher.internal.procs.ThrowException
import org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlan
import org.neo4j.cypher.rendering.QueryRenderer
import org.neo4j.dbms.api.DatabaseLimitReachedException
import org.neo4j.exceptions.CantCompileQueryException
import org.neo4j.exceptions.CypherExecutionException
import org.neo4j.exceptions.DatabaseAdministrationOnFollowerException
import org.neo4j.exceptions.InvalidArgumentException
import org.neo4j.exceptions.Neo4jException
import org.neo4j.graphdb.security.AuthorizationViolationException
import org.neo4j.internal.kernel.api.security.AbstractSecurityLog
import org.neo4j.internal.kernel.api.security.AccessMode
import org.neo4j.internal.kernel.api.security.AdminActionOnResource
import org.neo4j.internal.kernel.api.security.AdminActionOnResource.DatabaseScope
import org.neo4j.internal.kernel.api.security.PermissionState
import org.neo4j.internal.kernel.api.security.SecurityAuthorizationHandler
import org.neo4j.internal.kernel.api.security.SecurityContext
import org.neo4j.internal.kernel.api.security.Segment
import org.neo4j.kernel.api.exceptions.Status
import org.neo4j.kernel.api.exceptions.Status.HasStatus
import org.neo4j.kernel.database.NamedDatabaseId
import org.neo4j.kernel.database.NormalizedDatabaseName
import org.neo4j.kernel.impl.api.security.RestrictedAccessMode
import org.neo4j.kernel.impl.query.TransactionalContext.DatabaseMode
import org.neo4j.server.security.systemgraph.UserSecurityGraphComponent
import org.neo4j.values.storable.BooleanValue
import org.neo4j.values.storable.LongValue
import org.neo4j.values.storable.TextValue
import org.neo4j.values.storable.UTF8StringValue
import org.neo4j.values.storable.Value
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.VirtualValues

/**
 * This runtime takes on queries that work on the system database, such as multi-database and security administration commands.
 * The planning requirements for these are much simpler than normal Cypher commands, and as such the runtime stack is also different.
 */
case class DozerDbAdministrationCommandRuntime(
  normalExecutionEngine: ExecutionEngine,
  resolver: DependencyResolver,
  extraLogicalToExecutable: PartialFunction[LogicalPlan, AdministrationCommandRuntimeContext => ExecutionPlan] =
    DozerDbAdministrationCommandRuntime.emptyLogicalToExecutable
) extends AdministrationCommandRuntime {

  override def name: String = "dozerdb enhanced community administration-commands"

  private lazy val securityAuthorizationHandler =
    new SecurityAuthorizationHandler(resolver.resolveDependency(classOf[AbstractSecurityLog]))

  private val config: Config = resolver.resolveDependency(classOf[Config])

  private val maximumGdbsAllowed: Integer = config.get(DozerDbSettings.max_databases)

  private lazy val userSecurity: UserSecurityGraphComponent =
    resolver.resolveDependency(classOf[UserSecurityGraphComponent])

  def throwCantCompile(unknownPlan: LogicalPlan): Nothing = {
    throw new CantCompileQueryException(
      s"Plan is not a recognized database administration command in community edition: ${unknownPlan.getClass.getSimpleName}"
    )
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
    }.sorted.mkString(" and/or ")
  }

  private[internal] def adminActionErrorMessage(
    permissionState: PermissionState,
    actions: Seq[AdministrationAction]
  ) = {
    val allUnassignable = actions.forall(_.isInstanceOf[UnassignableAction])
    val missingPrivilegeHelpMessageSuffix = if (allUnassignable) "" else s" $checkShowUserPrivilegesText"

    permissionState match {
      case PermissionState.EXPLICIT_DENY =>
        s"Permission denied for ${prettifyActionName(actions: _*)}.$missingPrivilegeHelpMessageSuffix"
      case PermissionState.NOT_GRANTED =>
        val reason = if (allUnassignable) "cannot be" else "has not been"
        s"Permission $reason granted for ${prettifyActionName(actions: _*)}.$missingPrivilegeHelpMessageSuffix"
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
      securityAuthorizationHandler,
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
          securityAuthorizationHandler,
          (_, securityContext) => checkActions(actions, securityContext),
          violationMessage = adminActionErrorMessage,
          source = getSource(maybeSource, context)
        )

    // Check Admin Rights for DBMS commands or self
    case AssertAllowedDbmsActionsOrSelf(user, actions) =>
      context => checkAdminRightsForDBMSOrSelf(user, actions)(context)

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
          securityAuthorizationHandler,
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
    case ShowUsers(source, withAuth, symbols, yields, returns) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        ShowUsersExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planShowUsers(
          symbols,
          withAuth,
          yields,
          returns,
          sourcePlan
        )

    // SHOW CURRENT USER
    case ShowCurrentUser(symbols, yields, returns) => _ =>
        ShowUsersExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planShowCurrentUser(
          symbols,
          yields,
          returns
        )

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
          PrivilegeGQLCodeEntity.User(),
          userNamePropKey,
          fromUserName,
          toUserName,
          params => {
            val toName = runtimeStringValue(toUserName, params)
            NameValidator.assertValidUsername(toName)
          }
        )(sourcePlan, normalExecutionEngine, securityAuthorizationHandler)

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
        SetOwnPasswordExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler, config).planSetOwnPassword(
          newPassword,
          currentPassword,
          sourcePlan
        )

    case StopDatabase(source: AdministrationCommandLogicalPlan, databaseName: DatabaseName) => (context) => {

        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val nameValue: Value = nameFields.nameValue
        // TopologyGraphDbmsModel.DATABASE_NAME
        // Cypher query to drop the database node from the system graph
        UpdatingSystemCommandExecutionPlan(
          "" +
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

    case StartDatabase(source: AdministrationCommandLogicalPlan, databaseName: DatabaseName) => (context) => {

        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val nameValue: Value = nameFields.nameValue
        // TopologyGraphDbmsModel.DATABASE_NAME
        // Cypher query to drop the database node from the system graph
        UpdatingSystemCommandExecutionPlan(
          "" +
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

    case DropDatabase(source, databaseName, additionalAction, forceComposite, aliasAction) => (context) => {
        val config: Config = resolver.resolveDependency(classOf[Config])

        val nameFields: DatabaseNameFields = getDatabaseNameFields(
          "databaseName",
          databaseName
        )
        val parameterTransformer = ParameterTransformer()
          .convert(nameFields.nameConverter)

        // Cypher query to drop the database node from the system graph
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

    /*
    Function Name: CreateDatabase

    The `CreateDatabase` function is used to create a new graph database within Neo4j. This function handles the setup, configuration, and execution of creating a new database, ensuring the data is properly validated and stored.

    Parameters:
    1. `source`: This refers to the source of the database from where the new database is created, usually an existing database.
    2. `databaseName`: The name of the new database to be created.
    3. `_`: An anonymous parameter ignored in the execution of the function.

    Description:
    The function begins by resolving and retrieving the default Neo4j `Config` object. It is used to get the name of the default graph database from the settings.

    An array `valuePropNames` is defined, comprising keys used for setting the properties of the new database node.

    The `getNameFields` function is called for name validation. It creates a `NormalizedDatabaseName` object and validates the name against Neo4j's `DatabaseNameValidator`. If validation fails, an `InvalidArgumentException` is thrown.

    Post validation, the name value is retrieved and used in the following steps.

    The function then initializes an `UpdatingSystemCommandExecutionPlan`. This plan includes the creation of a new database node in Neo4j and setting its properties like `created_at`, `default`, `name`, `status`, and `uuid`. These properties are set using values from a VirtualValues map, which combines the property names (`valuePropNames`) with their corresponding values.

    In case of an error during execution, it is handled by `QueryHandler.handleError`. If an error occurs, an `IllegalStateException` is thrown, with an error message indicating the failure of the database creation.

    Finally, the function applies the `source` to the `fullLogicalToExecutable` function to compile and execute the database creation.

    Return Value:
    The function returns a new execution plan `UpdatingSystemCommandExecutionPlan` that includes the creation of a new database with the provided name and default properties.

    Errors:
    If the database name is invalid, an `InvalidArgumentException` will be thrown with the validation error message. If the database creation fails during the execution of the plan, an `IllegalStateException` is thrown indicating that the new database could not be created.
     */

    /**
     * case class CreateDatabase(
     * source: AdministrationCommandLogicalPlan,
     * databaseName: Either[String, Parameter],
     * options: Options,
     * ifExistsDo: IfExistsDo,
     * isComposite: Boolean,
     * topology: Option[Topology]
     * )(implicit idGen: IdGen) extends DatabaseAdministrationLogicalPlan(Some(source))
     *
     */
    case CreateDatabase(source, databaseName, _, _, _, _) => (context) => {
        // TODO: Put this at top in main class.
        val config: Config = resolver.resolveDependency(classOf[Config])
        val defaultGdbNameFromConfig: String = config.get(GraphDatabaseSettings.initial_default_database)

        val nameFields = getNameFields(
          "databaseName",
          databaseName,
          valueMapper = nameString => {
            val normalizedDatabaseName: NormalizedDatabaseName = new NormalizedDatabaseName(nameString)
            try {
              DatabaseNameValidator.validateExternalDatabaseName(normalizedDatabaseName)
            } catch {
              case exception: IllegalArgumentException =>
                throw new InvalidArgumentException(exception.getMessage)
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
             | database.default =  $$default,
             | database.name = $$`${nameFields.nameKey}`,
             | database.status = $$status,
             | database.uuid = randomUUID()
             | RETURN database.name as name, database.status as status, database.uuid as uuid
        """.stripMargin,
          VirtualValues.map(
            Array(nameFields.nameKey, "default", "status"),
            Array(
              nameFields.nameValue,
              Values.booleanValue(nameFields.nameValue.equals(defaultGdbNameFromConfig)),
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
          parameterTransformer =
            parameterTransformer
        )
      } // End case CreateDatabase

    case AssertManagementActionNotBlocked(action: AdministrationAction) => context =>
        AuthorizationAndPredicateExecutionPlan(
          securityAuthorizationHandler,
          (params, securityContext) =>
            Seq((
              action,
              securityContext.allowsAdminAction(
                new AdminActionOnResource(
                  ActionMapper.asKernelAction(action),
                  new DatabaseScope(""),
                  Segment.ALL
                )
              )
            )),
          violationMessage = adminActionErrorMessage
        )

    case EnsureNameIsNotAmbiguous(source, databaseName, isComposite) =>
      (context) => fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context)

    case AssertCanDropDatabase(
        source: PrivilegePlan,
        databaseName: DatabaseName, // NamespacedName from parent.
        action: DbmsAction
      ) => (context) =>
        AuthorizationAndPredicateExecutionPlan(
          securityAuthorizationHandler,
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
          violationMessage = adminActionErrorMessage
          // source = getSource(source, context)
        )

    // TODO: We need to implement this better.
    case EnsureDatabaseSafeToDelete(
        source: AdministrationCommandLogicalPlan,
        databaseName: DatabaseName,
        aliasAction: DropDatabaseAliasAction
      ) => (context) =>
        val valuePropNames: Array[String] = Array("name")
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
            valuePropNames,
            Array(
              nameValue
            )
          ),
          QueryHandler
            .handleNoResult(params =>
              Some(ThrowException(
                new CypherExecutionException(
                  s"Database not found:  '${runtimeStringValue(databaseName, params)}'."
                )
              ))
            )
            .handleResult((_, name, params) => {
              // val nameString = name.asInstanceOf[String].toString()
              Continue
            }),
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )

    case EnsureValidNonSystemDatabase(source, command, databaseName, action, aliasName) => (context) =>
        val valuePropNames: Array[String] = Array("name")
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
            valuePropNames,
            Array(
              nameValue
            )
          ),
          QueryHandler
            .handleNoResult(params =>
              Some(ThrowException(
                new CypherExecutionException(
                  s"Database not found:  '${runtimeStringValue(databaseName, params)}'."
                )
              ))
            )
            .handleResult((_, name, params) => {
              val nameString = name.asInstanceOf[UTF8StringValue].stringValue()
              // If the nameString is the system database then throw an error
              if (NamedDatabaseId.SYSTEM_DATABASE_NAME.equalsIgnoreCase(nameString)) {
                throw new CypherExecutionException(
                  "You can not delete the system database."
                );
              }
              Continue
            }),
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )
    case EnsureValidNumberOfDatabases(source) => (context) =>
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
                throw new DatabaseLimitReachedException(
                  "You have reached the limit on the number of databases you can create."
                );
              }
              Continue
            }),
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        )

    // SHOW DATABASES | SHOW DEFAULT DATABASE | SHOW HOME DATABASE | SHOW DATABASE foo
    case ShowDatabase(scope, verbose, symbols, yields, returns) => _ =>
        ShowDatabasesExecutionPlanner(
          resolver,
          normalExecutionEngine,
          securityAuthorizationHandler
        )
          .planShowDatabases(scope, verbose, symbols, yields, returns)

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

    case DoNothingIfExists(source, command, label, name, valueMapper) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfExists(
          command,
          label,
          name,
          valueMapper,
          sourcePlan
        )

    case DoNothingIfDatabaseNotExists(source, command, name, operation, databaseTypeFilter) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfDatabaseNotExists(
          command,
          name,
          operation,
          sourcePlan,
          databaseTypeFilter
        )

    case DoNothingIfDatabaseExists(source, command, name, databaseTypeFilter) => context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        DoNothingExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler).planDoNothingIfDatabaseExists(
          command,
          name,
          sourcePlan,
          databaseTypeFilter
        )

    // Ensure that the role or user exists before being dropped
    case EnsureNodeExists(source, command, entity, name, valueMapper, extraFilter, labelDescription, action) =>
      context =>
        val sourcePlan: Option[ExecutionPlan] =
          Some(fullLogicalToExecutable.applyOrElse(source, throwCantCompile).apply(context))
        EnsureNodeExistsExecutionPlanner(normalExecutionEngine, securityAuthorizationHandler)
          .planEnsureNodeExists(command, entity, name, valueMapper, extraFilter, labelDescription, action, sourcePlan)

    // SUPPORT PROCEDURES (need to be cleared before here)
    case SystemProcedureCall(_, call, returns, _, checkCredentialsExpired) => _ =>
        SystemProcedureCallPlanner(normalExecutionEngine, securityAuthorizationHandler).planSystemProcedureCall(
          call,
          returns,
          checkCredentialsExpired
        )
    case CheckNativeAuthentication() => _ =>
        val usernameKey = internalKey("username")
        val nativeAuth = internalKey("nativelyAuthenticated")

        def currentUser(p: MapValue): String = p.get(usernameKey).asInstanceOf[TextValue].stringValue()

        UpdatingSystemCommandExecutionPlan(
          "CheckNativeAuthentication",
          normalExecutionEngine,
          securityAuthorizationHandler,
          s"RETURN $$`$nativeAuth` AS nativelyAuthenticated",
          MapValue.EMPTY,
          QueryHandler
            .handleError {
              case (error: HasStatus, p) if error.status() == Status.Cluster.NotALeader =>
                DatabaseAdministrationOnFollowerException.notALeader(
                  "ALTER CURRENT USER SET PASSWORD",
                  s"User '${currentUser(p)}' failed to alter their own password",
                  error
                )
              case (error: Neo4jException, _) => error
              case (error, p) =>
                new CypherExecutionException(s"User '${currentUser(p)}' failed to alter their own password.", error)
            }
            .handleResult((_, value, _) => {
              if (value eq BooleanValue.TRUE) Continue
              else ThrowException(new AuthorizationViolationException("`ALTER CURRENT USER` is not permitted."))
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
          checkCredentialsExpired = false
        )
    // Non-administration commands that are allowed on system database, e.g. SHOW PROCEDURES
    case AllowedNonAdministrationCommands(statement) => context =>
        // While running against system will override most pre-parser options.
        // However, we shouldn't override the Cypher version,
        // so let's prepend the inner query with the relevant Cypher version.
        val versionName = context.runtimeContext.cypherVersion.versionName
        val versionString = s"CYPHER $versionName "
        SystemCommandExecutionPlan(
          "AllowedNonAdministrationCommand",
          normalExecutionEngine,
          securityAuthorizationHandler,
          versionString + QueryRenderer.render(statement),
          MapValue.EMPTY,
          // If we have a non admin command executing in the system database, forbid it to make reads / writes
          // from the system graph. This is to prevent queries such as SHOW PROCEDURES YIELD * RETURN ()--()
          // from leaking nodes from the system graph: the ()--() would return empty results
          modeConverter = s => s.withMode(new RestrictedAccessMode(s.mode(), AccessMode.Static.ACCESS))
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
