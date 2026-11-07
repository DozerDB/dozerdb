# Procedures DozerDB provides

Neo4j Community ships the procedures its documentation lists for Community
Edition. DozerDB adds, with Neo4j's name, signature, mode and description, the
procedures that the Neo4j Operations Manual documents for Enterprise Edition
where DozerDB implements the feature behind them. `SHOW PROCEDURES` lists them
beside Neo4j's; the plugin JAR, the standalone archives and the Docker image
carry the same procedures.

DozerDB is an independent open source project. Neo4j, Inc. has not reviewed or
endorsed this documentation. The descriptions below are the ones the Neo4j
Operations Manual **2026.10** gives on its *Procedures* reference page; that page
applies to these procedures as it stands.

| Procedure | Mode | Admin only | Description |
| --- | --- | --- | --- |
| `dbms.setConfigValue(setting :: STRING, value :: STRING)` | `DBMS` | Yes | Update a given setting value. Passing an empty value results in removing the configured value and falling back to the default value. Changes do not persist and are lost if the server is restarted. In a clustered environment, `dbms.setConfigValue` affects only the cluster member it is run against. |

## `dbms.setConfigValue`

Changes a dynamic setting for the running server without a restart, as the
Operations Manual's *Update dynamic settings* page describes:

```cypher
CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')
```

- Only a setting that Neo4j declares dynamic can be changed; the `dynamic` column
  of `SHOW SETTINGS` (or `CALL dbms.listConfig()`) says which. Every
  `db.logs.query.*` setting is dynamic, for example.
- The value is the text you would write in `neo4j.conf`, parsed and validated by
  the setting's own rules. An empty value (`''`) removes the configured value and
  the setting falls back to its default.
- The change applies to the next query and is lost at the next restart; to keep
  it, write it to `neo4j.conf` as well.
- An unknown setting, a setting that is not dynamic, or a value the setting does
  not accept fails with a message that names the setting and the reason, and the
  configuration is unchanged.
- The call works against a user database and against the `system` database, and
  `debug.log` records every change with the calling user.
- The procedure is marked admin-only, as Neo4j's is: the server refuses a caller
  whose security context has not been granted admin procedures. DozerDB uses Neo4j
  Community's security model, in which every authenticated user holds full DBMS
  rights, so every user who can log in can call it.

In the Docker image, settings passed as `NEO4J_*` environment variables are the
server's startup configuration; `dbms.setConfigValue` changes the running
container the same way and the variables apply again at the next start.
