/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
import { render, screen } from '@testing-library/react'
import React from 'react'

import { version as packageVersion } from 'project-root/package.json'
import { About } from './About'

declare const global: Record<string, unknown>

beforeAll(() => {
  // Defined by webpack's DefinePlugin in the real build
  global.__BUILD_NUMBER__ = undefined
  global.__GIT_HASH__ = undefined
  global.__BUILT_AT__ = undefined
})

test('About shows the DozerDB and Neo4j versions of a calendar release and links to its tag', () => {
  render(
    <About
      serverVersion="2026.10.1"
      serverEdition="enterprise"
      packageVersion="2026.10.1-1.2.0"
    />
  )

  const link = screen.getByText('DozerDB 1.2.0 (Neo4j 2026.10.1)')
  expect(link.getAttribute('href')).toBe(
    'https://github.com/DozerDB/dozerdb/releases/tag/2026.10.1-1.2.0'
  )
  expect(screen.getByText(/Neo4j Server version:/).textContent).toBe(
    'Neo4j Server version: 2026.10.1 (DozerDB)'
  )
})

test('About splits the package version at the first hyphen only', () => {
  render(
    <About
      serverVersion={null}
      serverEdition={null}
      packageVersion="5.26.32-1.2.0"
    />
  )

  const link = screen.getByText('DozerDB 1.2.0 (Neo4j 5.26.32)')
  expect(link.getAttribute('href')).toBe(
    'https://github.com/DozerDB/dozerdb/releases/tag/5.26.32-1.2.0'
  )
  expect(screen.queryByText(/Neo4j Server version:/)).toBeNull()
})

test('About renders the shipped Browser package version as the release', () => {
  const hyphen = packageVersion.indexOf('-')

  render(<About serverVersion={null} serverEdition={null} />)

  const link = screen.getByText(
    `DozerDB ${packageVersion.slice(hyphen + 1)} (Neo4j ${packageVersion.slice(
      0,
      hyphen
    )})`
  )
  expect(link.getAttribute('href')).toBe(
    `https://github.com/DozerDB/dozerdb/releases/tag/${packageVersion}`
  )
})
