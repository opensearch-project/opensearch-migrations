"""Tests for SIGPIPE handling in CLI entry points.

Regression coverage for
https://github.com/opensearch-project/opensearch-migrations/issues/2803
"""

import shutil
import os
import subprocess
import sys
import textwrap

import pytest

skip_on_windows = pytest.mark.skipif(
    sys.platform == "win32",
    reason="SIGPIPE is POSIX-only",
)

skip_if_not_installed = pytest.mark.skipif(
    not shutil.which("workflow"),
    reason="workflow CLI not installed",
)


# ---------- Unit tests: verify handler is installed at import time ----------

@skip_on_windows
def test_console_cli_import_installs_sigpipe_sig_dfl():
    """Importing console_link.cli must restore default SIGPIPE handling."""
    script = textwrap.dedent("""
        import signal
        import console_link.cli  # noqa: F401
        handler = signal.getsignal(signal.SIGPIPE)
        assert handler == signal.SIG_DFL, f"Expected SIG_DFL, got {handler!r}"
        print("OK")
    """)
    result = subprocess.run(
        [sys.executable, "-c", script],
        capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"
    assert "OK" in result.stdout


@skip_on_windows
def test_workflow_cli_import_installs_sigpipe_sig_dfl():
    """Same guarantee for the workflow CLI entry point."""
    script = textwrap.dedent("""
        import signal
        import console_link.workflow.cli  # noqa: F401
        handler = signal.getsignal(signal.SIGPIPE)
        assert handler == signal.SIG_DFL, f"Expected SIG_DFL, got {handler!r}"
        print("OK")
    """)
    result = subprocess.run(
        [sys.executable, "-c", script],
        capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"
    assert "OK" in result.stdout


# ---------- End-to-end: actual bug reproduction ----------


def _workflow_sample_env(tmp_path):
    """Provide the real CLI subprocess with a tiny packaged-sample stand-in."""
    sample_path = tmp_path / "sample.yaml"
    sample_path.write_text("sourceClusters: {}\n")

    scripts_dir = tmp_path / "configProcessor"
    scripts_dir.mkdir()

    env = os.environ.copy()
    env["CONFIG_PROCESSOR_DIR"] = str(scripts_dir)
    env["MIGRATION_SAMPLE_CONFIG_PATH"] = str(sample_path)
    return env


@skip_on_windows
@skip_if_not_installed
@pytest.mark.parametrize("consumer", [
    "head -0",
    "head",
    "true",
    ":",
    "grep -m1 sourceClusters",
    "sed -n 1p",
])
def test_workflow_configure_sample_pipe_no_traceback(consumer, tmp_path):
    """`workflow configure sample | <consumer>` must not print a Python traceback."""
    proc = subprocess.run(
        f"workflow configure sample | {consumer}",
        shell=True, capture_output=True, text=True, timeout=60,
        env=_workflow_sample_env(tmp_path),
    )
    assert "Traceback" not in proc.stderr, (
        f"Unexpected traceback for consumer={consumer!r}:\n{proc.stderr}"
    )
    assert "Broken pipe" not in proc.stderr, (
        f"Unexpected BrokenPipe message for consumer={consumer!r}:\n{proc.stderr}"
    )


@skip_on_windows
@skip_if_not_installed
def test_workflow_configure_sample_full_read_still_works(tmp_path):
    """Sanity check: the fix must not break the happy path (full stdout read)."""
    proc = subprocess.run(
        "workflow configure sample",
        shell=True, capture_output=True, text=True, timeout=60,
        env=_workflow_sample_env(tmp_path),
    )
    assert proc.returncode == 0, f"exit={proc.returncode} stderr={proc.stderr}"
    assert "sourceClusters" in proc.stdout
    assert "Traceback" not in proc.stderr
