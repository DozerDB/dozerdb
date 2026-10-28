import { gte } from 'semver'
import { stripScheme } from 'services/boltscheme.utils'
import { GlobalState } from 'shared/globalState'
import { inDesktop } from 'shared/modules/app/appDuck'
import {
  getConnectedHost,
  getUseDb,
  isConnected,
  isConnectedAuraHost
} from 'shared/modules/connections/connectionsDuck'
import {
  Database,
  findDatabaseByNameOrAlias,
  getClientsAllowTelemetry,
  getDatabases,
  getSemanticVersion,
  isServerConfigDone,
  NAME,
  shouldAllowOutgoingConnections,
  SYSTEM_DB,
  VERSION_FOR_CLUSTER_ROLE_IN_SHOW_DB
} from 'shared/modules/dbMeta/dbMetaDuck'

export function getCurrentDatabase(state: GlobalState): Database | null {
  const dbName = getUseDb(state)
  if (dbName) {
    return findDatabaseByNameOrAlias(state, dbName) ?? null
  }

  return null
}

export function isSystemOrCompositeDb(db: Database): boolean {
  return db?.name === SYSTEM_DB || db?.type === 'composite'
}

export const getClusterRoleForCurrentDb = (state: GlobalState) => {
  const version = getSemanticVersion(state)
  if (!version) return null

  if (gte(version, VERSION_FOR_CLUSTER_ROLE_IN_SHOW_DB)) {
    // In a cluster setup, there are many databases with the same name, often one per member
    // So our "cluster role" is the role we have on the database that lives
    // at the adress we're connected to
    const dbName = getUseDb(state)
    const host = getConnectedHost(state)
    if (dbName && host) {
      const databases = getDatabases(state)
      const currentDb = databases
        .filter(db => db.address === stripScheme(host))
        .find(database => database.name === dbName)
      return currentDb?.role
    } else {
      return null
    }
  } else {
    return state[NAME].role
  }
}

export type TelemetrySettingSource =
  | 'AURA'
  | 'BROWSER_SETTING'
  | 'NEO4J_CONF'
  | 'DESKTOP_SETTING'
  | 'SETTINGS_NOT_LOADED'

function usedTelemetrySettingSource(
  state: GlobalState
): TelemetrySettingSource {
  if (!isConnected(state) || !isServerConfigDone(state)) {
    return 'SETTINGS_NOT_LOADED'
  }

  if (
    !shouldAllowOutgoingConnections(state) ||
    !getClientsAllowTelemetry(state)
  ) {
    return 'NEO4J_CONF'
  }

  if (inDesktop(state)) {
    return 'DESKTOP_SETTING'
  }

  if (isConnectedAuraHost(state)) {
    return 'AURA'
  }

  return 'BROWSER_SETTING'
}

export type TelemetrySettings = {
  allowUserStats: boolean
  allowCrashReporting: boolean
  source: TelemetrySettingSource
}
/* DozerDb Turns off any outgoing telemetry. */
export const getTelemetrySettings = (state: GlobalState): TelemetrySettings => {
  const source = usedTelemetrySettingSource(state)

  const rules: Record<
    TelemetrySettingSource,
    {
      allowUserStats: boolean
      allowCrashReporting: boolean
    }
  > = {
    SETTINGS_NOT_LOADED: {
      allowCrashReporting: false,
      allowUserStats: false
    },
    DESKTOP_SETTING: {
      allowCrashReporting: false,
      allowUserStats: false
    },
    AURA: {
      allowCrashReporting: false,
      allowUserStats: false
    },
    BROWSER_SETTING: {
      allowCrashReporting: false,
      allowUserStats: false
    },
    NEO4J_CONF: {
      allowCrashReporting: false,
      allowUserStats: false
    }
  }

  return { source, ...rules[source] }
}
