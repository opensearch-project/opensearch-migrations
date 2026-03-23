"""Shared CRD constants and utilities for migration commands."""

import fnmatch

CRD_GROUP = 'migrations.opensearch.org'
CRD_VERSION = 'v1alpha1'


def has_glob(pattern):
    """Check if a string contains glob wildcard characters."""
    return any(c in pattern for c in '*?[')


def match_names(names, pattern):
    """Filter a list of names by exact match or glob pattern."""
    if has_glob(pattern):
        return [n for n in names if fnmatch.fnmatch(n, pattern)]
    return [n for n in names if n == pattern]
