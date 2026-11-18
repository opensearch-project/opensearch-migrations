"""Bounds for blocking calls made by workflow services.

A dependency that stops answering must become a reported error rather than a
request, background operation, or observation refresh that never finishes.
Normal latencies are far below these limits: Kubernetes and Argo reads return
in well under a second, and one config-processor invocation takes about a
second. The limits only need to separate "slow" from "not coming back".
"""

import subprocess
from typing import Optional, Tuple

import requests
import urllib3


# (connect, read) seconds. The read bound is the longest silence tolerated
# while waiting for or receiving a response, so large responses still work.
HttpTimeout = Tuple[float, Optional[float]]
KUBERNETES_REQUEST_TIMEOUT: HttpTimeout = (5.0, 30.0)
ARGO_REQUEST_TIMEOUT: HttpTimeout = (5.0, 30.0)
# Followed log streams are legitimately silent for long periods, so only the
# connection attempt is bounded.
STREAMING_REQUEST_TIMEOUT: HttpTimeout = (5.0, None)

NODE_VERSION_TIMEOUT_SECONDS = 15
CONFIG_PROCESSOR_TIMEOUT_SECONDS = 60
WORKFLOW_SUBMISSION_TIMEOUT_SECONDS = 120


class CommandTimeout(RuntimeError):
    """A bounded child process was stopped after exceeding its limit."""


TIMEOUT_EXCEPTION_TYPES = (
    CommandTimeout,
    subprocess.TimeoutExpired,
    requests.exceptions.Timeout,
    urllib3.exceptions.TimeoutError,
    TimeoutError,
)


def is_timeout(error: BaseException) -> bool:
    """Return whether an error, or anything it wraps, is a timeout."""
    seen = set()
    current: Optional[BaseException] = error
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, TIMEOUT_EXCEPTION_TYPES):
            return True
        reason = getattr(current, "reason", None)
        if isinstance(reason, BaseException) and is_timeout(reason):
            return True
        current = current.__cause__ or current.__context__
    return False
