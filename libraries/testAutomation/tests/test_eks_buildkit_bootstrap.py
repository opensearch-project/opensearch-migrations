"""Exercise BuildKit startup failures without Docker or a Kubernetes cluster."""

import os
import stat
import subprocess
from pathlib import Path
from urllib.parse import urlencode

import pytest


BACKEND = Path(__file__).parents[3] / "buildImages/backends/eksKubernetesBuildkit.sh"

# Stubs shared by the harnesses. kubectl renders a kubeconfig whose current-context is the requested
# one and records which KUBECONFIG it read. `docker buildx create` records the KUBECONFIG that buildx
# would store in the node endpoint. A plain `docker buildx inspect` (no --bootstrap) reports
# $EXISTING_BUILDER, or that the builder does not exist when it is unset.
STUBS = r"""
export MIGRATIONS_REPO_ROOT_DIR="$REPO_ROOT"
export HOME="$STATE_DIR/home"
source "$BACKEND"

record() { printf '%s\n' "$*" >> "$STATE_DIR/calls"; }
kubectl() {
  record "kubectl $*"
  if [[ "$1 $2" == "config current-context" ]]; then
    printf '%s\n' "${AMBIENT_CONTEXT:-}"
    return
  fi
  if [[ "$1 $2" == "config view" ]]; then
    record "kubectl-read-kubeconfig ${KUBECONFIG:-}"
    local arg context=""
    for arg in "$@"; do
      case "$arg" in --context=*) context="${arg#--context=}" ;; esac
    done
    if [[ "$context" == "${MISSING_CONTEXT:-}" ]]; then
      printf 'error: cannot locate context %s\n' "$context" >&2
      return 1
    fi
    printf 'apiVersion: v1\nkind: Config\ncurrent-context: %s\nserver: %s\n' \
      "$context" "${CONTEXT_SERVER:-https://requested.example}"
  fi
}
existing_builder_status() {
  local kubeconfig="$1" endpoint='kubernetes:///builder-migration-eks-test?deployment'
  printf 'Name:          builder-migration-eks-test\nDriver:        kubernetes\n\nNodes:\n'
  printf 'Name:                  builder-amd64\n'
  printf 'Endpoint:              %s=builder-amd64&kubeconfig=%s\n' "$endpoint" "$kubeconfig"
  printf 'Status:                %s\n\n' "${EXISTING_STATUS:-running}"
  printf 'Name:                  builder-arm64\n'
  printf 'Endpoint:              %s=builder-arm64&kubeconfig=%s\n' \
    "$endpoint" "${EXISTING_ARM64_KUBECONFIG-$kubeconfig}"
  printf 'Status:                running\n'
}
docker_inspect_existing() {
  if [[ -z "${EXISTING_BUILDER+set}" ]]; then
    printf 'ERROR: no builder %s found\n' "$3" >&2
    return 1
  fi
  existing_builder_status "$EXISTING_BUILDER"
}
"""

HARNESS = STUBS + r"""
export KUBE_CONTEXT="${KUBE_CONTEXT-migration-eks-test}"
printf '0' > "$STATE_DIR/clock"
printf '0' > "$STATE_DIR/attempts"

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
docker() {
  record "docker $*"
  # Match the older Kubernetes driver on Jenkins: unknown options fail at create.
  if [[ "$1 $2" == "buildx create" ]]; then
    record "buildx-create-kubeconfig ${KUBECONFIG-<unset>}"
    local arg
    for arg in "$@"; do
      case "$arg" in
        --driver-opt=timeout=*)
          printf 'ERROR: invalid driver option timeout for driver kubernetes\n' >&2
          return 1
          ;;
      esac
    done
  fi
  if [[ "$1 $2" != "buildx inspect" ]]; then return 0; fi
  if [[ " $* " != *" --bootstrap "* ]]; then
    docker_inspect_existing "$@"
    return
  fi
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
printf '%s' "${BUILDX_BUILDER:-}" > "$STATE_DIR/buildx_builder"
"""

# Runs $OPERATION against an existing (or missing) builder described by the stubs above.
OPERATION_HARNESS = STUBS + r"""
export KUBE_CONTEXT="${KUBE_CONTEXT-migration-eks-test}"
docker() {
  record "docker $*"
  if [[ "$1 $2" == "buildx inspect" ]]; then
    docker_inspect_existing "$@"
  fi
}
eval "$OPERATION"
printf '%s' "${BUILDX_BUILDER:-}" > "$STATE_DIR/buildx_builder"
"""

RELEASE_HARNESS = r"""
export MIGRATIONS_REPO_ROOT_DIR="$REPO_ROOT"
export KUBE_CONTEXT=migration-eks-test
export BUILDKIT_HELM_ARGS="--set-string nodeClassName=${NODE_CLASS}"
source "$BACKEND"

record() { printf '%s\n' "$*" >> "$STATE_DIR/calls"; }
helm() {
  record "helm $*"
  if [[ "$*" == *" status buildkit -n buildkit"* ]]; then
    [[ "${RELEASE_EXISTS:-true}" == "true" ]]
    return
  fi
  [[ "$*" == "upgrade --install buildkit "* ]] || return 99
}
ensure_eks_buildkit_release
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


def run_operation(tmp_path, operation, **overrides):
    env = {
        **os.environ,
        "BACKEND": str(BACKEND),
        "REPO_ROOT": str(BACKEND.parents[2]),
        "STATE_DIR": str(tmp_path),
        "OPERATION": operation,
        **overrides,
    }
    completed = subprocess.run(
        ["/bin/bash", "-c", OPERATION_HARNESS],
        env=env, capture_output=True, text=True, timeout=10,
    )
    calls_file = tmp_path / "calls"
    calls = calls_file.read_text().splitlines() if calls_file.exists() else []
    return completed, calls


def pinned_kubeconfig(tmp_path, kubeconfig_dir=None):
    directory = Path(kubeconfig_dir) if kubeconfig_dir else tmp_path / "home/.kube/buildx"
    return directory / "builder-migration-eks-test.kubeconfig"


def endpoint_value(path):
    """Encode a path the way buildx stores KUBECONFIG in a kubernetes:// endpoint (url.Values)."""
    return urlencode({"kubeconfig": str(path)})[len("kubeconfig="):]


def write_pinned_kubeconfig(path, context="migration-eks-test", server="https://requested.example"):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"apiVersion: v1\nkind: Config\ncurrent-context: {context}\nserver: {server}\n")


def run_release_reconcile(tmp_path, **overrides):
    env = {
        **os.environ,
        "BACKEND": str(BACKEND),
        "REPO_ROOT": str(BACKEND.parents[2]),
        "STATE_DIR": str(tmp_path),
        "NODE_CLASS": "migrations-tagged",
        **overrides,
    }
    completed = subprocess.run(
        ["/bin/bash", "-c", RELEASE_HARNESS],
        env=env,
        capture_output=True,
        text=True,
        timeout=10,
    )
    calls_file = tmp_path / "calls"
    calls = calls_file.read_text().splitlines() if calls_file.exists() else []
    return completed, calls


def test_existing_buildkit_release_is_reconciled_with_nodeclass_and_reuses_values(tmp_path):
    completed, calls = run_release_reconcile(tmp_path)
    assert completed.returncode == 0, completed.stderr
    upgrade = next(call for call in calls if " upgrade --install buildkit " in call)
    assert "--reuse-values" in upgrade
    assert "--set-string nodeClassName=migrations-tagged" in upgrade
    assert "--set skipBuildJob=true" in upgrade


def test_new_buildkit_release_uses_resolved_nodeclass_without_reuse_values(tmp_path):
    completed, calls = run_release_reconcile(tmp_path, RELEASE_EXISTS="false", NODE_CLASS="default")
    assert completed.returncode == 0, completed.stderr
    upgrade = next(call for call in calls if " upgrade --install buildkit " in call)
    assert "--reuse-values" not in upgrade
    assert "--set-string nodeClassName=default" in upgrade


@pytest.mark.parametrize("failure_rc", ["0", "1"])
def test_retries_until_both_native_builders_are_running(tmp_path, failure_rc):
    completed, calls = run_bootstrap(tmp_path, INSPECT_FAILURE_RC=failure_rc)
    assert completed.returncode == 0, completed.stderr
    inspections = [call for call in calls if call.startswith("docker buildx inspect ") and "--bootstrap" in call]
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


def test_older_driver_retries_cold_start_within_the_configured_deadline(tmp_path):
    completed, calls = run_bootstrap(
        tmp_path, INSPECT_SECONDS="120", BUILDKIT_BOOTSTRAP_TIMEOUT="5m",
    )
    assert completed.returncode == 0, completed.stderr
    create_calls = [call for call in calls if call.startswith("docker buildx create ")]
    assert len(create_calls) == 2
    assert "--node=builder-amd64" in create_calls[0]
    assert "--node=builder-arm64" in create_calls[1]
    assert "timeout 300s" in calls
    assert "timeout 170s" in calls
    assert calls.count("sleep 10") == 1
    assert calls.count("docker buildx inspect builder-migration-eks-test --bootstrap") == 2


def test_ready_builder_does_not_retry(tmp_path):
    completed, calls = run_bootstrap(tmp_path, READY_ON_ATTEMPT="1")
    assert completed.returncode == 0, completed.stderr
    assert calls.count("docker buildx inspect builder-migration-eks-test --bootstrap") == 1
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


def test_builder_is_pinned_to_the_requested_context_not_the_ambient_one(tmp_path):
    completed, calls = run_bootstrap(
        tmp_path, READY_ON_ATTEMPT="1", AMBIENT_CONTEXT="migration-eks-other",
        KUBECONFIG="/caller/one.yaml:/caller/two.yaml",
    )
    assert completed.returncode == 0, completed.stderr
    kubeconfig = pinned_kubeconfig(tmp_path)
    # The pinned file is rendered from the caller's (multi-file) KUBECONFIG for KUBE_CONTEXT only.
    assert "kubectl config view --minify --flatten --context=migration-eks-test" in calls
    assert "kubectl-read-kubeconfig /caller/one.yaml:/caller/two.yaml" in calls
    assert "current-context: migration-eks-test" in kubeconfig.read_text()
    assert stat.S_IMODE(kubeconfig.stat().st_mode) == 0o600
    # Both nodes are created with it, so buildx records it in their endpoints.
    created_with = [call for call in calls if call.startswith("buildx-create-kubeconfig ")]
    assert created_with == [f"buildx-create-kubeconfig {kubeconfig}"] * 2
    assert not any("current-context" in call for call in calls)


def test_builder_is_scoped_with_buildx_builder_instead_of_changing_the_global_builder(tmp_path):
    completed, calls = run_bootstrap(tmp_path, READY_ON_ATTEMPT="1")
    assert completed.returncode == 0, completed.stderr
    assert not any(call.startswith("docker buildx use") for call in calls)
    assert (tmp_path / "buildx_builder").read_text() == "builder-migration-eks-test"


def test_without_kube_context_the_builder_follows_the_callers_kubeconfig_as_before(tmp_path):
    completed, calls = run_bootstrap(
        tmp_path, READY_ON_ATTEMPT="1", KUBE_CONTEXT="", AMBIENT_CONTEXT="migration-eks-ambient",
        KUBECONFIG="/caller/config.yaml",
    )
    assert completed.returncode == 0, completed.stderr
    assert not any(call.startswith("kubectl config view") for call in calls)
    created_with = [call for call in calls if call.startswith("buildx-create-kubeconfig ")]
    assert created_with == ["buildx-create-kubeconfig /caller/config.yaml"] * 2
    assert "docker buildx rm builder-migration-eks-ambient" in calls
    assert calls.count("docker buildx inspect builder-migration-eks-ambient --bootstrap") == 1
    assert not (tmp_path / "home/.kube/buildx").exists()


def test_unknown_context_fails_before_creating_a_builder(tmp_path):
    completed, calls = run_bootstrap(tmp_path, MISSING_CONTEXT="migration-eks-test")
    assert completed.returncode != 0
    assert "could not resolve kube context 'migration-eks-test'" in completed.stderr
    assert not any(call.startswith("docker buildx create") for call in calls)
    assert not list(pinned_kubeconfig(tmp_path).parent.glob("*"))


def test_unpinned_existing_builder_is_replaced_without_using_the_ambient_context(tmp_path):
    # A builder from before this change has kubeconfig= empty, so buildx would use the ambient context.
    completed, calls = run_bootstrap(tmp_path, READY_ON_ATTEMPT="1", EXISTING_BUILDER="")
    assert completed.returncode == 0, completed.stderr
    assert "docker buildx rm --keep-daemon builder-migration-eks-test" in calls
    assert "docker buildx rm builder-migration-eks-test" not in calls
    assert ("kubectl --context=migration-eks-test -n buildkit delete deployment "
            "builder-amd64 builder-arm64 --ignore-not-found") in calls
    assert calls.index("docker buildx rm --keep-daemon builder-migration-eks-test") < next(
        i for i, call in enumerate(calls) if call.startswith("docker buildx create"))


def test_missing_builder_is_created_without_deleting_anything_on_the_cluster(tmp_path):
    completed, calls = run_bootstrap(tmp_path, READY_ON_ATTEMPT="1")
    assert completed.returncode == 0, completed.stderr
    assert not any(" delete " in call for call in calls if call.startswith("kubectl "))


@pytest.mark.parametrize("kubeconfig_dir", [None, "dir with space/é"])
def test_pinned_builder_is_removed_through_buildx_and_its_kubeconfig_deleted(tmp_path, kubeconfig_dir):
    overrides = {}
    if kubeconfig_dir:
        kubeconfig_dir = tmp_path / kubeconfig_dir
        overrides["BUILDKIT_KUBECONFIG_DIR"] = str(kubeconfig_dir)
    kubeconfig = pinned_kubeconfig(tmp_path, kubeconfig_dir)
    write_pinned_kubeconfig(kubeconfig)
    completed, calls = run_operation(
        tmp_path,
        "export BUILDX_BUILDER=builder-migration-eks-test; remove_eks_buildx_builder builder-migration-eks-test",
        EXISTING_BUILDER=endpoint_value(kubeconfig), **overrides,
    )
    assert completed.returncode == 0, completed.stderr
    assert "docker buildx rm builder-migration-eks-test" in calls
    assert not any("--keep-daemon" in call or " delete " in call for call in calls)
    assert not kubeconfig.exists()
    assert (tmp_path / "buildx_builder").read_text() == ""


@pytest.mark.parametrize("existing", [
    pytest.param({"EXISTING_BUILDER": ""}, id="created-without-kubeconfig"),
    pytest.param({"EXISTING_BUILDER": "%2Fcaller%2Fconfig.yaml"}, id="ambient-kubeconfig"),
    pytest.param({"EXISTING_ARM64_KUBECONFIG": ""}, id="one-node-unpinned"),
    pytest.param({"PINNED_FILE": "missing"}, id="pinned-file-deleted"),
])
def test_builder_that_could_reach_another_cluster_is_never_removed_through_buildx(tmp_path, existing):
    kubeconfig = pinned_kubeconfig(tmp_path)
    if existing.get("PINNED_FILE") != "missing":
        write_pinned_kubeconfig(kubeconfig)
    overrides = {"EXISTING_BUILDER": endpoint_value(kubeconfig), **existing}
    overrides.pop("PINNED_FILE", None)
    completed, calls = run_operation(
        tmp_path, "remove_eks_buildx_builder builder-migration-eks-test", **overrides,
    )
    assert completed.returncode == 0, completed.stderr
    assert "docker buildx rm builder-migration-eks-test" not in calls
    assert "docker buildx rm --keep-daemon builder-migration-eks-test" in calls
    assert ("kubectl --context=migration-eks-test -n buildkit delete deployment "
            "builder-amd64 builder-arm64 --ignore-not-found") in calls
    assert not kubeconfig.exists()


def test_healthy_pinned_builder_is_reused(tmp_path):
    kubeconfig = pinned_kubeconfig(tmp_path)
    write_pinned_kubeconfig(kubeconfig)
    completed, _ = run_operation(
        tmp_path, "eks_buildx_builder_is_ready builder-migration-eks-test",
        EXISTING_BUILDER=endpoint_value(kubeconfig),
    )
    assert completed.returncode == 0, completed.stderr


@pytest.mark.parametrize("case", [
    pytest.param({"EXISTING_BUILDER": ""}, id="unpinned"),
    pytest.param({"EXISTING_STATUS": "inactive"}, id="not-running"),
    pytest.param({"CONTEXT_SERVER": "https://repointed.example"}, id="context-now-names-another-cluster"),
    pytest.param({"PINNED_FILE": "missing"}, id="pinned-file-deleted"),
    pytest.param({"EXISTING_BUILDER": None}, id="no-builder"),
])
def test_builder_that_is_not_pinned_current_and_healthy_is_not_reused(tmp_path, case):
    kubeconfig = pinned_kubeconfig(tmp_path)
    if case.get("PINNED_FILE") != "missing":
        write_pinned_kubeconfig(kubeconfig)
    overrides = {"EXISTING_BUILDER": endpoint_value(kubeconfig), **case}
    overrides.pop("PINNED_FILE", None)
    if overrides["EXISTING_BUILDER"] is None:
        del overrides["EXISTING_BUILDER"]
    completed, calls = run_operation(
        tmp_path, "eks_buildx_builder_is_ready builder-migration-eks-test", **overrides,
    )
    assert completed.returncode != 0
    assert not any(call.startswith("docker buildx rm") for call in calls)


def test_reuse_check_without_kube_context_only_requires_running_nodes(tmp_path):
    completed, calls = run_operation(
        tmp_path, "eks_buildx_builder_is_ready builder-migration-eks-test",
        KUBE_CONTEXT="", EXISTING_BUILDER="",
    )
    assert completed.returncode == 0, completed.stderr
    assert not any(call.startswith("kubectl config view") for call in calls)


@pytest.mark.parametrize("value, expected", [
    ("/Users/me/.kube/buildx/builder-a.kubeconfig", "%2FUsers%2Fme%2F.kube%2Fbuildx%2Fbuilder-a.kubeconfig"),
    ("/tmp/dir with space/a+b&c=d~_-.é", None),
])
def test_query_escape_matches_go_url_values_encoding(value, expected):
    completed = subprocess.run(
        ["/bin/bash", "-c", 'source "$BACKEND"; query_escape "$VALUE"'],
        capture_output=True, text=True, timeout=10,
        env={**os.environ, "BACKEND": str(BACKEND), "MIGRATIONS_REPO_ROOT_DIR": str(BACKEND.parents[2]),
             "VALUE": value},
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == (expected or endpoint_value(value))
