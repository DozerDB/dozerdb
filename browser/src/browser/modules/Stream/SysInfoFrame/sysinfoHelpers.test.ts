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

import { responseHandler, sysinfoQuery } from './sysinfoHelpers'

const settings = {
  databaseName: 'neo4j',
  namespacesEnabled: true,
  metricsPrefix: 'neo4j'
}

function record(group: string, name: string, value: number) {
  const fields: Record<string, unknown> = {
    group,
    name,
    attributes: { Value: { value } }
  }
  return { get: (key: string) => fields[key] }
}

describe('sysinfo store size', () => {
  test('queries store.size.full, the documented name of the total store size', () => {
    const query = sysinfoQuery(settings)

    expect(query).toContain(
      'CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.store.size.full")'
    )
    expect(query).toContain(
      'CALL dbms.queryJmx("neo4j.metrics:name=neo4j.database.neo4j.store.size.database")'
    )
    expect(query).not.toContain('store.size.total')
  })

  test('shows store.size.full as the total store size', () => {
    const setState = jest.fn()

    responseHandler(setState)({
      result: {
        records: [
          record(
            'Store Size',
            'neo4j.metrics:name=neo4j.database.neo4j.store.size.full',
            3 * 1024 * 1024
          ),
          record(
            'Store Size',
            'neo4j.metrics:name=neo4j.database.neo4j.store.size.database',
            2 * 1024 * 1024
          )
        ]
      }
    })

    expect(setState.mock.calls[0][0].storeSizes).toEqual([
      { label: 'Total', value: '3.00 MiB' },
      { label: 'Database', value: '2.00 MiB' }
    ])
  })
})
