#
# Copyright (c) DozerDB
# ALL RIGHTS RESERVED.
#
# DozerDb is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
"""The distribution version: the grammar of a DozerDB release's identity.

A distribution version is the exact upstream Neo4j tag joined by one hyphen to the DozerDB version:
`2026.10.1-1.2.0`, `5.26.32-1.2.0`. The upstream tag is kept verbatim (digits, dots and letters, starting with a
digit, any number of fields, `.LTS`-style suffixes allowed) and never contains a hyphen, so the composite is read
by splitting at its first hyphen. The DozerDB version is strict `MAJOR.MINOR.PATCH`: numeric fields without
leading zeros and no pre-release or build identifiers.

The composite is not a SemVer; this module composes, splits and validates it and never orders two of them.
Every script that names, reads or checks a distribution version goes through it.
"""
import re

_UPSTREAM_TAG = re.compile(r'[0-9][0-9A-Za-z.]*')
_NUMERIC = re.compile(r'[0-9]+')
# A MAJOR.MINOR.PATCH followed by a SemVer pre-release (-rc.1) or build (+abc) part.
_PRE_RELEASE = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+[-+].*')


class DistributionVersionError(ValueError):
    """A distribution version, upstream tag or DozerDB version breaks the grammar; the message names the rule."""


def validate_upstream_tag(tag):
    """Return `tag` unchanged if it is a valid upstream Neo4j tag, else raise DistributionVersionError."""
    if not isinstance(tag, str) or tag == '':
        raise DistributionVersionError('upstream tag must not be empty')
    if '-' in tag:
        raise DistributionVersionError(f'upstream tag must not contain a hyphen: {tag!r}')
    if not _UPSTREAM_TAG.fullmatch(tag):
        raise DistributionVersionError(
            f'upstream tag must start with a digit and contain only digits, dots and letters: {tag!r}')
    if '..' in tag or tag.endswith('.'):
        raise DistributionVersionError(f'upstream tag must not contain an empty field: {tag!r}')
    return tag


def validate_dozerdb_version(version):
    """Return `version` unchanged if it is a valid DozerDB version, else raise DistributionVersionError."""
    if not isinstance(version, str) or version == '':
        raise DistributionVersionError('DozerDB version must not be empty')
    if _PRE_RELEASE.fullmatch(version):
        raise DistributionVersionError(
            f'DozerDB version must not carry pre-release or build identifiers: {version!r}')
    fields = version.split('.')
    if len(fields) != 3:
        raise DistributionVersionError(
            f'DozerDB version must be MAJOR.MINOR.PATCH (three fields), got {len(fields)}: {version!r}')
    for name, field in zip(('MAJOR', 'MINOR', 'PATCH'), fields):
        if not _NUMERIC.fullmatch(field):
            raise DistributionVersionError(f'DozerDB version {name} must be a non-negative integer: {version!r}')
        if len(field) > 1 and field.startswith('0'):
            raise DistributionVersionError(f'DozerDB version {name} must not have a leading zero: {version!r}')
    return version


def compose(upstream_tag, dozerdb_version):
    """The distribution version `<upstream tag>-<DozerDB version>`, after validating both halves."""
    return f'{validate_upstream_tag(upstream_tag)}-{validate_dozerdb_version(dozerdb_version)}'


def split(distribution_version):
    """The pair (upstream tag, DozerDB version) of a distribution version, split at its first hyphen and both
    halves validated; raises DistributionVersionError naming the rule broken."""
    if not isinstance(distribution_version, str) or distribution_version == '':
        raise DistributionVersionError('distribution version must not be empty')
    if '-' not in distribution_version:
        raise DistributionVersionError(
            f'distribution version must be <upstream tag>-<DozerDB version> joined by a hyphen: '
            f'{distribution_version!r}')
    upstream_tag, dozerdb_version = distribution_version.split('-', 1)
    validate_upstream_tag(upstream_tag)
    if '-' in dozerdb_version and not _PRE_RELEASE.fullmatch(dozerdb_version):
        raise DistributionVersionError(
            f'distribution version must contain exactly one hyphen (the upstream tag never contains one): '
            f'{distribution_version!r}')
    validate_dozerdb_version(dozerdb_version)
    return upstream_tag, dozerdb_version


def validate(distribution_version):
    """Return `distribution_version` unchanged if it is valid, else raise DistributionVersionError."""
    split(distribution_version)
    return distribution_version
