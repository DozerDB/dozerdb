/*
 * Copyright (c) 2002-2021 "Neo4j,"
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

const fs = require('fs')
const path = require('path')
const ncp = require('ncp').ncp

function mkPath(dirPath) {
  if (!fs.existsSync(dirPath)) {
    try {
      fs.mkdirSync(dirPath)
    } catch (e) {
      mkPath(path.dirname(dirPath))
      mkPath(dirPath)
    }
  }
}

mkPath(path.join(__dirname, '../mvn'))
// The licence files are copied only after the dist copy has created mvn/browser: started concurrently, the
// single-file copies raced the directory copy and lost under load, leaving a JAR without LICENSE and LICENSES.txt.
ncp(
  path.join(__dirname, '../dist'),
  path.join(__dirname, '../mvn/browser'),
  err => {
    if (err) throw err
    for (const name of ['LICENSE', 'LICENSES.txt', 'NOTICE.txt']) {
      fs.copyFileSync(
        path.join(__dirname, '..', name),
        path.join(__dirname, '../mvn/browser', name)
      )
    }
  }
)
