"""Pytest configuration for workflow tests."""

import os
import warnings
from pathlib import Path
import pytest
import subprocess
import re


@pytest.fixture(autouse=True)
def set_config_processor_dir(monkeypatch):
    # Set required ETCD environment variables for tests
    monkeypatch.setenv("ETCD_SERVICE_HOST", "localhost")
    monkeypatch.setenv("ETCD_SERVICE_PORT_CLIENT", "2379")


def agradle(*args):
    """
    Mimics the shell function:
    - Walk upward from cwd to root looking for ./gradlew
    - Run that gradlew with the provided args
    - Return stdout (stripped)
    """
    dir_path = Path(os.getcwd())
    for parent in [dir_path] + list(dir_path.parents):
        gradlew = parent / "gradlew"
        if gradlew.exists() and os.access(gradlew, os.X_OK):
            result = subprocess.run(
                [str(gradlew), *args],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=True,
            )
            return result.stdout.strip()
    raise RuntimeError("No gradlew script found in ancestor directories")


CONFIG_RE = re.compile(r"CONFIG_PROCESSOR_DIR=(.*)")

@pytest.fixture(scope="session", autouse=True)
def ensure_config_processor_dir():
    """
    Ensures CONFIG_PROCESSOR_DIR is set.
    If not set, runs the Gradle confirmConfigProcessorStagingPath task once
    and extracts the value from its output.
    """
    if "CONFIG_PROCESSOR_DIR" in os.environ:
        return  # already set externally

    print("CONFIG_PROCESSOR_DIR not set — running Gradle task...")

    output = agradle(
        ":migrationConsole:confirmConfigProcessorStagingPath",
        "-q",
        "--console=plain"
    )

    # Look for the `CONFIG_PROCESSOR_DIR=/path` line
    match = CONFIG_RE.search(output)
    if not match:
        raise RuntimeError(
            "Gradle did not output CONFIG_PROCESSOR_DIR=. "
            f"Received:\n{output}"
        )

    path = match.group(1).strip()

    # Export for the remainder of the pytest session
    os.environ["CONFIG_PROCESSOR_DIR"] = path
    print(f"CONFIG_PROCESSOR_DIR set to: {path}")