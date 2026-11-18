import os
import subprocess
import time

import pytest
import requests
import urllib3

from console_link.workflow.services.script_runner import ScriptRunner, _run_process
from console_link.workflow.timeouts import CommandTimeout, is_timeout


def test_is_timeout_recognizes_wrapped_and_retried_timeouts():
    read_timeout = urllib3.exceptions.ReadTimeoutError(None, "/api", "Read timed out.")
    retries_exhausted = urllib3.exceptions.MaxRetryError(None, "/api", reason=read_timeout)
    try:
        try:
            raise requests.exceptions.ReadTimeout("argo read timed out")
        except requests.exceptions.ReadTimeout as error:
            raise RuntimeError("workflow unavailable") from error
    except RuntimeError as wrapped:
        wrapped_timeout = wrapped

    assert is_timeout(CommandTimeout("node timed out"))
    assert is_timeout(retries_exhausted)
    assert is_timeout(wrapped_timeout)
    assert not is_timeout(urllib3.exceptions.MaxRetryError(None, "/api", reason=ConnectionRefusedError()))
    assert not is_timeout(RuntimeError("other failure"))


def _wait_until_gone(pid: int, seconds: float = 5.0) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        time.sleep(0.05)
    return False


def test_run_process_timeout_stops_the_whole_process_group(tmp_path):
    pid_file = tmp_path / "grandchild.pid"

    with pytest.raises(CommandTimeout, match=r"Submission probe timed out after 0.5 seconds"):
        _run_process(
            ["bash", "-c", f"sleep 60 & echo $! > {pid_file}; wait"],
            timeout_seconds=0.5,
            label="Submission probe",
        )

    assert _wait_until_gone(int(pid_file.read_text()))


def test_run_process_matches_subprocess_run_results():
    completed = _run_process(
        ["bash", "-c", "cat; echo err >&2"],
        timeout_seconds=10,
        label="Echo",
        input="from stdin",
    )
    assert completed.returncode == 0
    assert completed.stdout == "from stdin"
    assert completed.stderr == "err\n"

    with pytest.raises(subprocess.CalledProcessError) as error:
        _run_process(
            ["bash", "-c", "echo out; echo bad >&2; exit 3"],
            timeout_seconds=10,
            label="Failing",
            check=True,
        )
    assert error.value.returncode == 3
    assert error.value.stdout == "out\n"
    assert error.value.stderr == "bad\n"


def test_config_processor_timeout_names_the_processor_command(tmp_path, monkeypatch):
    node = tmp_path / "node"
    node.write_text("#!/usr/bin/env bash\nif [ \"$1\" = --version ]; then echo v22.14.0; exit 0; fi\nsleep 60\n")
    node.chmod(0o755)
    monkeypatch.setenv("NODEJS", str(node))
    runner = ScriptRunner(script_dir=tmp_path, config_processor_timeout_seconds=0.5)

    with pytest.raises(CommandTimeout, match=r"Config processor command 'editConfig' timed out after 0.5 seconds"):
        runner.run_config_processor_node_script("editConfig", "state")
