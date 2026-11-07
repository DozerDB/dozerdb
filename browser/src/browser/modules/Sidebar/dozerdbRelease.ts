/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

export const DOZERDB_RELEASES_URL =
  'https://github.com/DozerDB/dozerdb/releases/tag/'

export interface DozerdbRelease {
  distributionVersion: string
  neo4jTag: string
  dozerdbVersion: string
  label: string
  releaseUrl: string
}

/**
 * Reads a distribution version `<neo4j-tag>-<dozerdb-version>` by splitting
 * at the first hyphen, as scripts/distribution_version.py does.
 * A version without a hyphen is shown whole as the DozerDB release.
 */
export function describeDozerdbRelease(
  distributionVersion: string
): DozerdbRelease {
  const releaseUrl = `${DOZERDB_RELEASES_URL}${distributionVersion}`
  const hyphen = distributionVersion.indexOf('-')
  if (hyphen <= 0 || hyphen === distributionVersion.length - 1) {
    return {
      distributionVersion,
      neo4jTag: '',
      dozerdbVersion: '',
      label: `DozerDB ${distributionVersion}`,
      releaseUrl
    }
  }
  const neo4jTag = distributionVersion.slice(0, hyphen)
  const dozerdbVersion = distributionVersion.slice(hyphen + 1)
  return {
    distributionVersion,
    neo4jTag,
    dozerdbVersion,
    label: `DozerDB ${dozerdbVersion} (Neo4j ${neo4jTag})`,
    releaseUrl
  }
}
