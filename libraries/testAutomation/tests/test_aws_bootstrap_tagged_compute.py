"""Exercise the tagged-compute handoff without contacting AWS or Kubernetes."""

import os
import re
import subprocess
from pathlib import Path

import pytest


BOOTSTRAP = (
    Path(__file__).parents[3] / "deployment/k8s/aws/aws-bootstrap.sh"
)


def run_handoff(tmp_path, **overrides):
    source = BOOTSTRAP.read_text()
    functions = []
    for name in ("wait_for_eks_config_update", "configure_tagged_auto_mode_compute"):
        match = re.search(rf"^{name}\(\) \{{\n.*?^\}}", source, re.MULTILINE | re.DOTALL)
        if match:
            functions.append(match.group())
    harness = r"""
set -euo pipefail
MIGRATIONS_EKS_CLUSTER_NAME=test-cluster
AWS_CFN_REGION=us-east-1
KUBE_CONTEXT=test-context
AUTO_MODE_TAGGED_NODE_CLASS=migrations-tagged
AUTO_MODE_BOOTSTRAP_NODE_POOL=migrations-bootstrap-pool
enforce_tags_on_create_for_tests="${ENFORCE:-true}"
touch "$STATE_DIR/default-nodeclass"

record() { printf '%s\n' "$*" >> "$STATE_DIR/calls"; }
sleep() { record "sleep $*"; }
ensure_auto_mode_tag_propagation_policy() { record allow-tags; }
ensure_auto_node_access_entry() { record node-access; }
emit_resource_tags_yaml() { printf '    MATestOwner: migrations-ci\n'; }
enforce_tags_on_cluster_role_for_tests() {
  record enforce-tags
  if [[ -f "$STATE_DIR/default-nodeclass" ]]; then
    echo "Default NodeClass still makes untagged RunInstances requests" >&2
    return 91
  fi
}
aws() {
  case "$1 $2" in
    "eks describe-cluster")
      printf 'arn:aws:iam::123456789012:role/NodeRole|sg-test|subnet-a,subnet-b|%s\n' \
        "${BUILTIN_POOLS-system,general-purpose}"
      ;;
    "eks update-cluster-config")
      record update-requested
      if [[ "${UPDATE_REQUEST_FAIL:-false}" == true ]]; then return 92; fi
      printf '%s\n' "${UPDATE_ID-update-123}"
      ;;
    "eks wait")
      # DescribeCluster can still report ACTIVE immediately after UpdateClusterConfig.
      record cluster-active
      ;;
    "eks describe-update")
      [[ "$*" == *"--name test-cluster"* && "$*" == *"--update-id update-123"* &&
         "$*" == *"--region us-east-1"* ]] || return 98
      if [[ "$*" != *"--query update.status"* ]]; then
        printf '{"update":{"status":"%s","errors":[{"errorMessage":"update failed"}]}}\n' \
          "${UPDATE_OUTCOME:-Successful}"
        return
      fi
      local polls=0 status=InProgress
      if [[ -f "$STATE_DIR/polls" ]]; then polls=$(cat "$STATE_DIR/polls"); fi
      polls=$((polls + 1))
      printf '%s' "$polls" > "$STATE_DIR/polls"
      if [[ "$polls" -ge 3 ]]; then status="${UPDATE_OUTCOME:-Successful}"; fi
      record "update-status $status"
      if [[ "$status" == Successful ]]; then touch "$STATE_DIR/update-complete"; fi
      printf '%s\n' "$status"
      ;;
    *) echo "Unexpected AWS call: $*" >&2; return 99 ;;
  esac
}
kubectl() {
  [[ "$1" == --context=test-context ]] || return 98
  shift
  case "$1" in
    apply) record apply-tagged-compute ;;
    wait)
      record wait-for-default-deletion
      [[ "$*" == *"--for=delete"* && "$*" == *"nodepool/system"* &&
         "$*" == *"nodepool/general-purpose"* && "$*" == *"nodeclass/default"* &&
         "$*" == *"--timeout="* ]] || return 98
      if [[ -n "${BUILTIN_POOLS-system,general-purpose}" ]]; then
        [[ -f "$STATE_DIR/update-complete" ]] || return 94
      fi
      if [[ "${DELETE_FAIL:-false}" == true ]]; then return 93; fi
      rm "$STATE_DIR/default-nodeclass"
      record default-deleted
      ;;
    *) echo "Unexpected kubectl call: $*" >&2; return 99 ;;
  esac
}
"""
    env = {**os.environ, "STATE_DIR": str(tmp_path), **overrides}
    program = harness + "\n".join(functions) + "\nconfigure_tagged_auto_mode_compute\n"
    completed = subprocess.run(
        ["/bin/bash", "-c", program],
        env=env, capture_output=True, text=True, timeout=10,
    )
    return completed, (tmp_path / "calls").read_text().splitlines()


def test_waits_for_the_update_and_default_nodeclass_before_enforcement(tmp_path):
    completed, calls = run_handoff(tmp_path)
    assert completed.returncode == 0, completed.stderr
    assert "cluster-active" not in calls
    assert calls.count("update-status InProgress") == 2
    assert calls.index("apply-tagged-compute") < calls.index("update-requested")
    assert calls.index("update-status Successful") < calls.index("default-deleted")
    assert calls.index("default-deleted") < calls.index("enforce-tags")


@pytest.mark.parametrize("status", ["Failed", "Cancelled", "Unexpected"])
def test_unsuccessful_update_stops_before_enforcement(tmp_path, status):
    completed, calls = run_handoff(tmp_path, UPDATE_OUTCOME=status)
    assert completed.returncode != 0
    assert status in completed.stderr
    assert "enforce-tags" not in calls
    assert "wait-for-default-deletion" not in calls


def test_in_progress_update_has_a_bounded_wait(tmp_path):
    completed, calls = run_handoff(tmp_path, UPDATE_OUTCOME="InProgress")
    assert completed.returncode != 0
    assert "Timed out" in completed.stderr
    assert calls.count("update-status InProgress") == 40
    assert "enforce-tags" not in calls


@pytest.mark.parametrize("update_id", ["", "None"])
def test_missing_update_id_does_not_enable_enforcement(tmp_path, update_id):
    completed, calls = run_handoff(tmp_path, UPDATE_ID=update_id)
    assert completed.returncode != 0
    assert "update ID" in completed.stderr
    assert "enforce-tags" not in calls


def test_rejected_update_does_not_enable_enforcement(tmp_path):
    completed, calls = run_handoff(tmp_path, UPDATE_REQUEST_FAIL="true")
    assert completed.returncode != 0
    assert "enforce-tags" not in calls


def test_failed_kubernetes_deletion_does_not_enable_enforcement(tmp_path):
    completed, calls = run_handoff(tmp_path, DELETE_FAIL="true")
    assert completed.returncode != 0
    assert "wait-for-default-deletion" in calls
    assert "enforce-tags" not in calls


def test_rerun_waits_for_leftover_kubernetes_resources_without_another_update(tmp_path):
    completed, calls = run_handoff(tmp_path, BUILTIN_POOLS="")
    assert completed.returncode == 0, completed.stderr
    assert "update-requested" not in calls
    assert calls.index("default-deleted") < calls.index("enforce-tags")


def test_tagged_compute_without_test_enforcement_still_waits_for_the_handoff(tmp_path):
    completed, calls = run_handoff(tmp_path, ENFORCE="false")
    assert completed.returncode == 0, completed.stderr
    assert "default-deleted" in calls
    assert "enforce-tags" not in calls
