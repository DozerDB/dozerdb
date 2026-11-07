#
# Copyright (c) DozerDB
# ALL RIGHTS RESERVED.
#
# DozerDb is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
"""The distribution-version grammar as scripts/distribution_version.py composes, splits and checks it."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import distribution_version  # noqa: E402
from distribution_version import DistributionVersionError  # noqa: E402


class ComposeAndSplit(unittest.TestCase):

    def test_composes_the_calendar_and_lts_identities(self):
        self.assertEqual('2026.10.1-1.2.0', distribution_version.compose('2026.10.1', '1.2.0'))
        self.assertEqual('5.26.32-1.2.0', distribution_version.compose('5.26.32', '1.2.0'))

    def test_splits_them_back_at_the_first_hyphen(self):
        self.assertEqual(('2026.10.1', '1.2.0'), distribution_version.split('2026.10.1-1.2.0'))
        self.assertEqual(('5.26.32', '1.2.0'), distribution_version.split('5.26.32-1.2.0'))

    def test_a_suffixed_upstream_tag_round_trips_verbatim(self):
        composed = distribution_version.compose('2026.10.1.LTS', '1.2.0')
        self.assertEqual('2026.10.1.LTS-1.2.0', composed)
        self.assertEqual(('2026.10.1.LTS', '1.2.0'), distribution_version.split(composed))

    def test_the_upstream_tag_is_not_assumed_to_have_three_fields(self):
        for tag in ('5', '5.26', '2026.10.1.2'):
            with self.subTest(tag=tag):
                self.assertEqual((tag, '10.0.12'), distribution_version.split(distribution_version.compose(tag, '10.0.12')))

    def test_validate_returns_a_valid_identity_unchanged(self):
        self.assertEqual('2026.10.1-0.0.0', distribution_version.validate('2026.10.1-0.0.0'))


class Rejections(unittest.TestCase):
    """Each invalid identifier is rejected with a message naming the rule it breaks."""

    def assertRejected(self, value, rule):
        with self.assertRaises(DistributionVersionError) as raised:
            distribution_version.validate(value)
        self.assertIn(rule, str(raised.exception))
        with self.assertRaises(DistributionVersionError):
            distribution_version.split(value)

    def test_the_old_four_part_identity(self):
        self.assertRejected('2026.10.1.1', 'joined by a hyphen')

    def test_a_v_prefix(self):
        self.assertRejected('v2026.10.1-1.2.0', 'upstream tag must start with a digit')

    def test_a_bare_dozerdb_version(self):
        self.assertRejected('1.2.0', 'joined by a hyphen')

    def test_a_two_field_dozerdb_version(self):
        self.assertRejected('2026.10.1-1.2', 'DozerDB version must be MAJOR.MINOR.PATCH')

    def test_a_pre_release_dozerdb_version(self):
        self.assertRejected('2026.10.1-1.2.0-rc.1', 'must not carry pre-release or build identifiers')

    def test_a_build_identifier(self):
        self.assertRejected('2026.10.1-1.2.0+abc', 'must not carry pre-release or build identifiers')

    def test_two_hyphens(self):
        self.assertRejected('2026.10-1-1.2.0', 'must contain exactly one hyphen')

    def test_an_empty_upstream_half(self):
        self.assertRejected('-1.2.0', 'upstream tag must not be empty')

    def test_an_empty_dozerdb_half(self):
        self.assertRejected('2026.10.1-', 'DozerDB version must not be empty')

    def test_an_empty_identity(self):
        self.assertRejected('', 'distribution version must not be empty')

    def test_a_leading_zero_in_any_dozerdb_field(self):
        for value in ('2026.10.1-01.2.0', '2026.10.1-1.02.0', '2026.10.1-1.2.00'):
            with self.subTest(value=value):
                self.assertRejected(value, 'must not have a leading zero')

    def test_a_non_numeric_dozerdb_field(self):
        for value in ('2026.10.1-1.x.0', '2026.10.1-a.2.0', '2026.10.1-1.2.LTS', '2026.10.1-1..0'):
            with self.subTest(value=value):
                self.assertRejected(value, 'must be a non-negative integer')

    def test_an_upstream_tag_with_an_empty_field(self):
        self.assertRejected('2026..1-1.2.0', 'upstream tag must not contain an empty field')


class ComposeRejections(unittest.TestCase):
    """compose validates both inputs, naming the rule broken."""

    def assertRejected(self, tag, version, rule):
        with self.assertRaises(DistributionVersionError) as raised:
            distribution_version.compose(tag, version)
        self.assertIn(rule, str(raised.exception))

    def test_an_upstream_tag_with_a_hyphen(self):
        self.assertRejected('2026.10-1', '1.2.0', 'upstream tag must not contain a hyphen')

    def test_an_empty_half(self):
        self.assertRejected('', '1.2.0', 'upstream tag must not be empty')
        self.assertRejected('2026.10.1', '', 'DozerDB version must not be empty')

    def test_a_dozerdb_version_breaking_the_grammar(self):
        self.assertRejected('2026.10.1', '1.2', 'DozerDB version must be MAJOR.MINOR.PATCH')
        self.assertRejected('2026.10.1', '1.2.0-rc.1', 'must not carry pre-release or build identifiers')
        self.assertRejected('2026.10.1', '1.02.0', 'must not have a leading zero')
        self.assertRejected('2026.10.1', '1.two.0', 'must be a non-negative integer')

    def test_a_v_prefixed_upstream_tag(self):
        self.assertRejected('v2026.10.1', '1.2.0', 'upstream tag must start with a digit')


if __name__ == '__main__':
    unittest.main()
