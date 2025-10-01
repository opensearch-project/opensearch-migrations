"""Utility classes and functions for the workflow library."""

from enum import Enum


class ExitCode(Enum):
    """Exit codes for command operations."""
    SUCCESS = 0
    FAILURE = 1
    INVALID_INPUT = 2
    NOT_FOUND = 3
    ALREADY_EXISTS = 4
    PERMISSION_DENIED = 5
