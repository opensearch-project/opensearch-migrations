"""Utility classes and functions for the workflow library."""

from enum import Enum
from ..models.store import WorkflowConfigStore


class ExitCode(Enum):
    """Exit codes for command operations."""
    SUCCESS = 0
    FAILURE = 1
    INVALID_INPUT = 2
    NOT_FOUND = 3
    ALREADY_EXISTS = 4
    PERMISSION_DENIED = 5


def get_store(ctx) -> WorkflowConfigStore:
    """Lazy initialization of WorkflowConfigStore"""
    if ctx.obj['store'] is None:
        ctx.obj['store'] = WorkflowConfigStore(namespace=ctx.obj['namespace'])
    return ctx.obj['store']
