"""Pytest configuration for workflow tests."""

import os
import warnings
from pathlib import Path
import pytest
import subprocess
import re
from kubernetes import config


DEFAULT_WORKFLOW_TEST_KUBE_CONTEXT = "kind-console-link-test"


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
                cwd=parent,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            if result.returncode != 0:
                raise RuntimeError(
                    f"Gradle command failed (exit {result.returncode}):\n"
                    f"  args: {[str(gradlew), *args]}\n"
                    f"  stdout: {result.stdout}\n"
                    f"  stderr: {result.stderr}"
                )
            return result.stdout.strip()
    raise RuntimeError("No gradlew script found in ancestor directories")


CONFIG_RE = re.compile(r"CONFIG_PROCESSOR_DIR=(.*)")
NODE_RE = re.compile(r"NODEJS_BIN=(.*)")


@pytest.fixture(scope="session", autouse=True)
def ensure_config_processor_dir():
    """
    Ensures CONFIG_PROCESSOR_DIR and NODEJS are set.
    If not set, runs the Gradle confirmConfigProcessorStagingPath task once
    and extracts the values from its output.
    """
    config_already_set = "CONFIG_PROCESSOR_DIR" in os.environ
    node_already_set = "NODEJS" in os.environ

    if config_already_set and node_already_set:
        return  # both already set externally

    print("CONFIG_PROCESSOR_DIR or NODEJS not set — running Gradle task...")

    output = agradle(
        ":migrationConsole:confirmConfigProcessorStagingPath",
        "-q",
        "--console=plain"
    )

    # Only set CONFIG_PROCESSOR_DIR if not already set
    if not config_already_set:
        config_match = CONFIG_RE.search(output)
        if not config_match:
            raise ValueError(f"Gradle did not output CONFIG_PROCESSOR_DIR=. Received:\n{output}")
        config_path = config_match.group(1).strip()
        os.environ["CONFIG_PROCESSOR_DIR"] = config_path
        print("CONFIG_PROCESSOR_DIR set to: {config_path}")

    # Only set NODEJS if not already set
    if not node_already_set:
        node_match = NODE_RE.search(output)
        if not node_match:
            raise ValueError(f"Gradle did not output NODEJS=. Received:\n{output}")
        node_path = node_match.group(1).strip()
        os.environ["NODEJS"] = node_path
        print(f"NODEJS set to: {node_path}")


def get_expected_workflow_test_kube_context():
    return os.environ.get("WORKFLOW_TEST_KUBE_CONTEXT", DEFAULT_WORKFLOW_TEST_KUBE_CONTEXT)


@pytest.fixture(scope="session")
def required_workflow_test_kube_context():
    """
    Require the active kube context to be the dedicated workflow test context.

    These workflow/reset integration tests intentionally run against a pre-created
    local Kind cluster or the equivalent CI cluster. They should never silently
    fall back to some unrelated local Kubernetes context.
    """
    expected_context = get_expected_workflow_test_kube_context()

    try:
        contexts, active_context = config.list_kube_config_contexts()
    except config.ConfigException as exc:
        pytest.fail(
            "Unable to load kubeconfig for workflow integration tests. "
            f"Expected active context {expected_context!r}. Underlying error: {exc}"
        )

    if not active_context:
        pytest.fail(
            "No active kube context is configured. "
            f"Expected active context {expected_context!r}."
        )

    active_name = active_context.get("name")
    if active_name != expected_context:
        pytest.fail(
            f"Refusing to run workflow integration tests against kube context {active_name!r}. "
            f"Expected {expected_context!r}. "
            "Run the local setup script first or switch contexts explicitly."
        )

    config.load_kube_config()
    return {
        "expected_context": expected_context,
        "active_context": active_name,
        "contexts": contexts,
    }
