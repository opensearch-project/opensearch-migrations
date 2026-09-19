"""Tests for bounded source and target cluster curl execution."""

from datetime import datetime, timezone

import pytest

from console_link.workflow.application.cluster_curl import (
    ClusterCurlService,
    ClusterCurlUnavailable,
)
from console_link.workflow.application.runtime_status import ConsoleCommandResult


class _Runner:
    def __init__(self, result):
        self.result = result
        self.commands = []

    def run(self, command):
        self.commands.append(command)
        return self.result


def test_cluster_curl_invokes_shared_console_command():
    runner = _Runner(ConsoleCommandResult(
        success=True,
        output="green open documents",
    ))
    service = ClusterCurlService(
        console_runner=runner,
        clock=lambda: datetime(2026, 9, 19, 18, 0, tzinfo=timezone.utc),
    )

    result = service.execute(
        "resource:sourceconfigs:source",
        "sourceconfigs",
        "source",
        method="get",
        path="_cat/indices",
    )

    assert runner.commands == [[
        "clusters",
        "curl",
        "-X",
        "GET",
        "source",
        "/_cat/indices",
    ]]
    assert result.success is True
    assert result.output == "green open documents"
    assert result.observed_at == "2026-09-19T18:00:00+00:00"


def test_cluster_curl_preserves_headers_body_and_command_errors():
    runner = _Runner(ConsoleCommandResult(
        success=False,
        output="",
        error="Connection refused",
    ))
    service = ClusterCurlService(console_runner=runner)

    result = service.execute(
        "resource:targetconfigs:target",
        "targetconfigs",
        "target",
        method="POST",
        path="/_search",
        headers=("Content-Type: application/json",),
        body='{"query":{"match_all":{}}}',
    )

    assert runner.commands == [[
        "clusters",
        "curl",
        "-X",
        "POST",
        "-H",
        "Content-Type: application/json",
        "--data",
        '{"query":{"match_all":{}}}',
        "target",
        "/_search",
    ]]
    assert result.success is False
    assert result.error == "Connection refused"


def test_cluster_curl_rejects_non_cluster_resources():
    service = ClusterCurlService(
        console_runner=_Runner(ConsoleCommandResult(True, "")),
    )

    with pytest.raises(
        ClusterCurlUnavailable,
        match="only available for source and target",
    ):
        service.execute(
            "resource:datasnapshots:snapshot",
            "datasnapshots",
            "snapshot",
            method="GET",
            path="/_cat/indices",
        )


def test_cluster_curl_uses_a_larger_bounded_response_by_default():
    service = ClusterCurlService()

    assert service.console_runner.max_characters == 500_000
