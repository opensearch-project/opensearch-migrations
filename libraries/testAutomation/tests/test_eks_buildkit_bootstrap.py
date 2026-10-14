"""Exercise BuildKit startup failures without Docker or a Kubernetes cluster."""

import os
import subprocess
from pathlib import Path

import pytest


BACKEND = Path(__file__).parents[3] / "buildImages/backends/eksKubernetesBuildkit.sh"

HARNESS = r"""
export MIGRATIONS_REPO_ROOT_DIR="$REPO_ROOT"
export KUBE_CONTEXT=migration-eks-test
source "$BACKEND"
printf '0' > "$STATE_DIR/clock"
printf '0' > "$STATE_DIR/attempts"

record() { printf '%s\n' "$*" >> "$STATE_DIR/calls"; }
date() { cat "$STATE_DIR/clock"; }
sleep() {
  record "sleep $*"
  printf '%s' "$(($(cat "$STATE_DIR/clock") + $1))" > "$STATE_DIR/clock"
}
run_with_optional_timeout() {
  record "timeout $1"
  shift
  "$@"
}
kubectl() { record "kubectl $*"; }
docker() {
  record "docker $*"
  if [[ "$1 $2" != "buildx inspect" ]]; then return 0; fi
  local attempt
  attempt=$(($(cat "$STATE_DIR/attempts") + 1))
  printf '%s' "$attempt" > "$STATE_DIR/attempts"
  printf '%s' "$(($(cat "$STATE_DIR/clock") + ${INSPECT_SECONDS:-0}))" > "$STATE_DIR/clock"
  if [[ "$attempt" -lt "${READY_ON_ATTEMPT:-2}" ]]; then
    printf 'ERROR: error dialing backend: remote error: tls: internal error\n' >&2
    printf 'Nodes:\nName: builder-amd64\nStatus: running\nName: builder-arm64\nStatus: inactive\n'
    return "${INSPECT_FAILURE_RC:-0}"
  fi
  printf 'Nodes:\nName: builder-amd64\nStatus: running\nName: builder-arm64\nStatus: running\n'
}
ensure_eks_buildx_builder
"""


def run_bootstrap(tmp_path, **overrides):
    env = {
        **os.environ,
        "BACKEND": str(BACKEND),
        "REPO_ROOT": str(BACKEND.parents[2]),
        "STATE_DIR": str(tmp_path),
        "BUILDKIT_BOOTSTRAP_TIMEOUT": "900s",
        "BUILDKIT_BOOTSTRAP_ATTEMPTS": "2",
        **overrides,
    }
    completed = subprocess.run(
        ["/bin/bash", "-c", HARNESS],
        env=env, capture_output=True, text=True, timeout=10,
    )
    calls_file = tmp_path / "calls"
    calls = calls_file.read_text().splitlines() if calls_file.exists() else []
    return completed, calls


@pytest.mark.parametrize("failure_rc", ["0", "1"])
def test_retries_until_both_native_builders_are_running(tmp_path, failure_rc):
    completed, calls = run_bootstrap(tmp_path, INSPECT_FAILURE_RC=failure_rc)
    assert completed.returncode == 0, completed.stderr
    inspections = [call for call in calls if call.startswith("docker buildx inspect ")]
    assert len(inspections) == 2
    assert all(call == "docker buildx inspect builder-migration-eks-test --bootstrap" for call in inspections)
    assert "sleep 10" in calls
    assert "timeout 900s" in calls
    assert "timeout 890s" in calls


def test_inactive_builder_exhausts_attempts_and_dumps_provisioning_diagnostics(tmp_path):
    completed, calls = run_bootstrap(tmp_path, READY_ON_ATTEMPT="99")
    assert completed.returncode != 0
    assert "exhausted 2 attempt(s)" in completed.stderr
    assert "kubectl --context=migration-eks-test describe pods -n buildkit" in calls
    assert "kubectl --context=migration-eks-test describe nodeclaims" in calls


def test_deadline_also_dumps_provisioning_diagnostics(tmp_path):
    completed, calls = run_bootstrap(
        tmp_path, READY_ON_ATTEMPT="99", INSPECT_SECONDS="900", BUILDKIT_BOOTSTRAP_ATTEMPTS="0",
    )
    assert completed.returncode != 0
    assert "did not complete within 900s" in completed.stderr
    assert calls.count("docker buildx inspect builder-migration-eks-test --bootstrap") == 1
    assert "kubectl --context=migration-eks-test describe nodeclaims" in calls
    assert not any(call.startswith("sleep ") for call in calls)


def test_both_kubernetes_drivers_receive_the_configured_cold_start_timeout(tmp_path):
    completed, calls = run_bootstrap(
        tmp_path, READY_ON_ATTEMPT="1", BUILDKIT_BOOTSTRAP_TIMEOUT="5m",
    )
    assert completed.returncode == 0, completed.stderr
    create_calls = [call for call in calls if call.startswith("docker buildx create ")]
    assert len(create_calls) == 2
    assert all("--driver-opt=timeout=300s" in call for call in create_calls)
    assert "timeout 300s" in calls
    assert not any(call.startswith("sleep ") for call in calls)


def test_invalid_attempt_limit_is_rejected_before_changing_the_builder(tmp_path):
    completed, calls = run_bootstrap(tmp_path, BUILDKIT_BOOTSTRAP_ATTEMPTS="invalid")
    assert completed.returncode != 0
    assert "must be a non-negative integer" in completed.stderr
    assert not any(call.startswith("docker ") for call in calls)


@pytest.mark.parametrize("status", [
    "",
    "Name: builder-amd64\nStatus: running\n",
    "Name: builder-amd64\nStatus: running\nName: builder-arm64\nError: unavailable\n",
    "Name: builder-amd64\nStatus: inactive\nName: builder-arm64\nStatus: running\n",
    "Name: unrelated-node\nStatus: running\nName: builder-arm64\nStatus: running\n",
])
def test_missing_or_unhealthy_native_nodes_are_not_accepted(status):
    completed = subprocess.run(
        ["/bin/bash", "-c", 'source "$BACKEND"; buildx_nodes_are_ready'],
        input=status, capture_output=True, text=True, timeout=10,
        env={**os.environ, "BACKEND": str(BACKEND), "MIGRATIONS_REPO_ROOT_DIR": str(BACKEND.parents[2])},
    )
    assert completed.returncode != 0
