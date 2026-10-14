"""Bounded console curl execution for source and target runtime views."""

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Optional, Sequence

from .runtime_status import BoundedConsoleRunner


SUPPORTED_CLUSTER_PLURALS = {"sourceconfigs", "targetconfigs"}
SUPPORTED_METHODS = {"GET", "POST", "PUT", "DELETE", "HEAD"}
MAX_RESPONSE_CHARACTERS = 500_000


class ClusterCurlUnavailable(RuntimeError):
    """The selected node cannot run a cluster curl request."""


@dataclass(frozen=True)
class ClusterCurlResult:
    node_id: str
    cluster_name: str
    observed_at: str
    method: str
    path: str
    success: bool
    output: str
    error: Optional[str] = None


class ClusterCurlService:
    """Invoke the shared console curl command for a concrete cluster alias."""

    def __init__(
        self,
        *,
        console_runner: Optional[Any] = None,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self.console_runner = console_runner or BoundedConsoleRunner(
            timeout_seconds=30,
            max_characters=MAX_RESPONSE_CHARACTERS,
        )
        self.clock = clock

    def execute(
        self,
        node_id: str,
        plural: str,
        cluster_name: str,
        *,
        method: str,
        path: str,
        headers: Sequence[str] = (),
        body: Optional[str] = None,
    ) -> ClusterCurlResult:
        if plural not in SUPPORTED_CLUSTER_PLURALS:
            raise ClusterCurlUnavailable(
                "Cluster curl is only available for source and target clusters."
            )
        normalized_method = method.strip().upper()
        if normalized_method not in SUPPORTED_METHODS:
            raise ClusterCurlUnavailable(
                f"Unsupported HTTP method: {normalized_method or method}"
            )
        normalized_path = path.strip()
        if not normalized_path:
            raise ClusterCurlUnavailable("A cluster API path is required.")
        if not normalized_path.startswith("/"):
            normalized_path = f"/{normalized_path}"

        command = [
            "clusters",
            "curl",
            "-X",
            normalized_method,
        ]
        for header in headers:
            command.extend(["-H", header])
        if body is not None and body != "":
            command.extend(["--data", body])
        command.extend([cluster_name, normalized_path])

        result = self.console_runner.run(command)
        command_error = result.error
        response_error = result.output.startswith("Error:")
        success = result.success and not response_error
        return ClusterCurlResult(
            node_id=node_id,
            cluster_name=cluster_name,
            observed_at=self.clock().isoformat(),
            method=normalized_method,
            path=normalized_path,
            success=success,
            output=result.output,
            error=command_error if not success else None,
        )
