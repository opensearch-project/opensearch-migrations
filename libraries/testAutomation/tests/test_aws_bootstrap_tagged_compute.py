"""Exercise the tagged-compute handoff without contacting AWS or Kubernetes."""

import json
import os
import re
import subprocess
from pathlib import Path

import pytest


BOOTSTRAP = (
    Path(__file__).parents[3] / "deployment/k8s/aws/aws-bootstrap.sh"
)


def extract_function(source, name):
    match = re.search(rf"^{name}\(\) \{{\n.*?^\}}", source, re.MULTILINE | re.DOTALL)
    assert match, f"could not find {name} in {BOOTSTRAP}"
    return match.group()


def run_handoff(tmp_path, **overrides):
    source = BOOTSTRAP.read_text()
    functions = [
        extract_function(source, name)
        for name in ("wait_for_eks_config_update", "configure_tagged_auto_mode_compute")
    ]
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
      if [[ "$*" == *"--for=condition=Ready"* ]]; then
        record wait-for-tagged-nodeclass
        [[ "$*" == *"nodeclass/migrations-tagged"* && "$*" == *"--timeout=5m"* ]] || return 98
        if [[ "${NODECLASS_READY_FAIL:-false}" == true ]]; then return 95; fi
        return 0
      fi
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
    assert calls.index("apply-tagged-compute") < calls.index("wait-for-tagged-nodeclass")
    assert calls.index("wait-for-tagged-nodeclass") < calls.index("update-requested")
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


def test_unready_tagged_nodeclass_stops_before_disabling_existing_compute(tmp_path):
    completed, calls = run_handoff(tmp_path, NODECLASS_READY_FAIL="true")
    assert completed.returncode != 0
    assert "did not become Ready" in completed.stderr
    assert "wait-for-tagged-nodeclass" in calls
    assert "update-requested" not in calls
    assert "wait-for-default-deletion" not in calls


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


def test_helm_values_nodeclass_override_is_detected_after_the_sentinel(tmp_path):
    source = BOOTSTRAP.read_text()
    chart_dir = tmp_path / "chart"
    chart_dir.mkdir()
    (chart_dir / "valuesEks.yaml").write_text("cluster:\n  useCustomKarpenterNodePool: true\n")
    user_values = tmp_path / "user-values.yaml"
    user_values.write_text("aws:\n  nodeClassName: explicit-class\n")

    harness = r"""
set -euo pipefail
user_helm_values="$USER_VALUES"
use_general_node_pool=false
ma_chart_dir="$CHART_DIR"
namespace=ma
STAGE=test
AWS_CFN_REGION=us-east-1
AWS_ACCOUNT=123456789012
MIGRATIONS_EKS_CLUSTER_NAME=test-cluster
KUBE_CONTEXT=test-context
user_node_class_override=""
resolve_chart_source() { :; }
helm() {
  printf 'helm %s\n' "$*" >> "$STATE_DIR/calls"
  cat <<EOF
apiVersion: karpenter.sh/v1
kind: NodePool
spec:
  template:
    spec:
      nodeClassRef:
        group: eks.amazonaws.com
        kind: NodeClass
        name: explicit-class
EOF
}
kubectl() {
  printf 'kubectl %s\n' "$*" >> "$STATE_DIR/calls"
}
"""
    program = "".join((
        harness,
        extract_function(source, "extract_rendered_node_class_names"),
        "\n",
        extract_function(source, "validate_helm_values_against_cluster"),
        "\nvalidate_helm_values_against_cluster\n",
        "printf 'override=%s\\n' \"$user_node_class_override\"\n",
    ))
    completed = subprocess.run(
        ["/bin/bash", "-c", program],
        env={
            **os.environ,
            "CHART_DIR": str(chart_dir),
            "USER_VALUES": str(user_values),
            "STATE_DIR": str(tmp_path),
        },
        capture_output=True,
        text=True,
        timeout=10,
    )

    assert completed.returncode == 0, completed.stderr
    assert "override=explicit-class" in completed.stdout
    helm_call = (tmp_path / "calls").read_text().splitlines()[0]
    assert re.search(rf"-f - -f \S+ -f {re.escape(str(user_values))}\b", helm_call)
    assert "--context=test-context apply --dry-run=server" in (tmp_path / "calls").read_text()


def node_pool(name, node_class):
    return {
        "metadata": {"name": name},
        "spec": {
            "template": {
                "spec": {
                    "nodeClassRef": {
                        "group": "eks.amazonaws.com",
                        "kind": "NodeClass",
                        "name": node_class,
                    },
                },
            },
        },
    }


def node_class(name, *, ready="True", tags=None):
    return {
        "metadata": {"name": name},
        "spec": {"tags": tags or {}},
        "status": {"conditions": [{"type": "Ready", "status": ready}]},
    }


def run_auto_mode_resolution(
    tmp_path,
    *,
    nodepools,
    nodeclasses,
    user_override="",
    explicit_tag=None,
    skip_cfn=True,
    explicit_values="/explicit-values.yaml",
):
    source = BOOTSTRAP.read_text()
    function_names = (
        "emit_resource_tags_yaml",
        "print_auto_mode_compute_state",
        "restore_auto_mode_node_class",
        "discover_auto_mode_node_class",
        "resolve_auto_mode_configuration",
        "emit_auto_mode_helm_values",
        "prepend_auto_mode_helm_values",
        "set_buildkit_node_class_helm_arg",
    )
    functions = [extract_function(source, name) for name in function_names]

    nodepools_file = tmp_path / "nodepools.json"
    nodeclasses_file = tmp_path / "nodeclasses.json"
    nodepools_file.write_text(json.dumps({"items": nodepools}))
    nodeclasses_file.write_text(json.dumps({"items": nodeclasses}))

    harness = r"""
set -euo pipefail
KUBE_CONTEXT=test-context
AUTO_MODE_TAGGED_NODE_CLASS=migrations-tagged
AUTO_MODE_BOOTSTRAP_NODE_POOL=migrations-bootstrap-pool
auto_mode_node_class=default
configure_tagged_auto_mode_compute_requested=false
user_node_class_override="${USER_OVERRIDE:-}"
skip_cfn_deploy="${SKIP_CFN:-true}"
use_general_node_pool=false
extra_helm_values="${EXPLICIT_VALUES:-}"
BUILDKIT_HELM_ARGS=""
tag_keys=()
tag_values=()
RESERVED_TAG_PREFIXES=('aws:' 'eks:' 'eks.amazonaws.com/' 'karpenter.sh/' 'kubernetes.io/cluster/')
if [[ -n "${EXPLICIT_TAG_KEY:-}" ]]; then
  tag_keys+=("$EXPLICIT_TAG_KEY")
  tag_values+=("$EXPLICIT_TAG_VALUE")
fi
validate_and_add_tag() {
  tag_keys+=("$1")
  tag_values+=("$2")
}
kubectl() {
  printf '%s\n' "$*" >> "$STATE_DIR/calls"
  if [[ "$1" != "--context=test-context" || "$2" != "get" ]]; then return 98; fi
  case "$3" in
    nodepools) cat "$NODEPOOLS_FILE" ;;
    nodeclasses) cat "$NODECLASSES_FILE" ;;
    nodeclass)
      jq -e --arg name "$4" '.items[] | select(.metadata.name == $name)' "$NODECLASSES_FILE"
      ;;
    *) return 99 ;;
  esac
}
"""
    program = harness + "\n".join(functions) + r"""
resolve_auto_mode_configuration
prepend_auto_mode_helm_values
set_buildkit_node_class_helm_arg
printf 'nodeClass=%s\n' "$auto_mode_node_class"
printf 'configure=%s\n' "$configure_tagged_auto_mode_compute_requested"
printf 'extraValues=%s\n' "$extra_helm_values"
printf 'buildkitArgs=%s\n' "$BUILDKIT_HELM_ARGS"
for i in "${!tag_keys[@]}"; do
  printf 'tag=%s=%s\n' "${tag_keys[$i]}" "${tag_values[$i]}"
done
cat "$auto_mode_values_file"
"""
    env = {
        **os.environ,
        "STATE_DIR": str(tmp_path),
        "NODEPOOLS_FILE": str(nodepools_file),
        "NODECLASSES_FILE": str(nodeclasses_file),
        "USER_OVERRIDE": user_override,
        "SKIP_CFN": str(skip_cfn).lower(),
        "EXPLICIT_VALUES": explicit_values,
    }
    if explicit_tag:
        env["EXPLICIT_TAG_KEY"], env["EXPLICIT_TAG_VALUE"] = explicit_tag
    completed = subprocess.run(
        ["/bin/bash", "-c", program],
        env=env,
        capture_output=True,
        text=True,
        timeout=10,
    )
    calls_file = tmp_path / "calls"
    calls = calls_file.read_text().splitlines() if calls_file.exists() else []
    return completed, calls


def test_skip_cfn_preserves_ready_custom_nodeclass_and_its_resource_tags(tmp_path):
    completed, calls = run_auto_mode_resolution(
        tmp_path,
        nodepools=[
            node_pool("migrations-bootstrap-pool", "team-compute"),
            node_pool("leftover-workload", "default"),
        ],
        nodeclasses=[
            node_class("team-compute", tags={"CostCenter": "1234", "Owner": "search"}),
        ],
    )

    assert completed.returncode == 0, completed.stderr
    assert "nodeClass=team-compute" in completed.stdout
    assert "configure=false" in completed.stdout
    assert 'nodeClassName: "team-compute"' in completed.stdout
    assert "buildkitArgs=--set-string nodeClassName=team-compute" in completed.stdout
    assert 'tag=CostCenter=1234' in completed.stdout
    assert 'tag=Owner=search' in completed.stdout
    assert calls[0].endswith("get nodepools -o json")
    assert any(call.endswith("get nodeclass team-compute -o json") for call in calls)


def test_skip_cfn_retains_ready_default_nodeclass_used_by_normal_cluster(tmp_path):
    completed, _ = run_auto_mode_resolution(
        tmp_path,
        nodepools=[
            node_pool("system", "default"),
            node_pool("general-purpose", "default"),
        ],
        nodeclasses=[node_class("default")],
    )

    assert completed.returncode == 0, completed.stderr
    assert "nodeClass=default" in completed.stdout
    assert 'nodeClassName: "default"' in completed.stdout
    assert "buildkitArgs=--set-string nodeClassName=default" in completed.stdout
    assert "tag=" not in completed.stdout


@pytest.mark.parametrize(
    ("nodepools", "nodeclasses", "message"),
    [
        (
            [node_pool("pool-a", "class-a"), node_pool("pool-b", "class-b")],
            [node_class("class-a"), node_class("class-b")],
            "discovery is ambiguous",
        ),
        ([node_pool("pool-a", "missing")], [], "does not exist"),
        ([], [], "no EKS Auto Mode NodeClass exists"),
        ([node_pool("pool-a", "class-a")], [node_class("class-a", ready="False")], "is not Ready"),
    ],
)
def test_skip_cfn_rejects_ambiguous_missing_or_unready_nodeclasses(
    tmp_path, nodepools, nodeclasses, message,
):
    completed, _ = run_auto_mode_resolution(
        tmp_path,
        nodepools=nodepools,
        nodeclasses=nodeclasses,
    )

    assert completed.returncode != 0
    assert message in completed.stderr
    assert "nodeClassName:" not in completed.stdout


def test_explicit_user_configuration_wins_over_discovery_and_generated_values_are_prepended(
    tmp_path,
):
    completed, calls = run_auto_mode_resolution(
        tmp_path,
        nodepools=[
            node_pool("pool-a", "class-a"),
            node_pool("pool-b", "class-b"),
        ],
        nodeclasses=[
            node_class("class-a"),
            node_class("class-b", tags={"Inherited": "must-not-win"}),
        ],
        user_override="class-b",
        explicit_tag=("Explicit", "wins"),
    )

    assert completed.returncode == 0, completed.stderr
    assert "nodeClass=class-b" in completed.stdout
    assert "configure=true" in completed.stdout
    assert "buildkitArgs=--set-string nodeClassName=class-b" in completed.stdout
    assert "tag=Explicit=wins" in completed.stdout
    assert "Inherited" not in completed.stdout
    assert not any(call.endswith("get nodepools -o json") for call in calls)
    match = re.search(r"extraValues=([^,\n]+),/explicit-values.yaml", completed.stdout)
    assert match, completed.stdout
    assert Path(match.group(1)).read_text().startswith(
        'aws:\n  nodeClassName: "class-b"\n',
    )


def test_explicit_tags_select_managed_nodeclass_without_cluster_discovery(tmp_path):
    completed, calls = run_auto_mode_resolution(
        tmp_path,
        nodepools=[
            node_pool("pool-a", "class-a"),
            node_pool("pool-b", "class-b"),
        ],
        nodeclasses=[],
        explicit_tag=("Explicit", "wins"),
    )

    assert completed.returncode == 0, completed.stderr
    assert "nodeClass=migrations-tagged" in completed.stdout
    assert "configure=true" in completed.stdout
    assert "tag=Explicit=wins" in completed.stdout
    assert not calls


def test_explicit_helm_nodeclass_disambiguates_and_restores_selected_tags(tmp_path):
    completed, calls = run_auto_mode_resolution(
        tmp_path,
        nodepools=[
            node_pool("pool-a", "class-a"),
            node_pool("pool-b", "class-b"),
        ],
        nodeclasses=[
            node_class("class-a", tags={"Class": "a"}),
            node_class("class-b", tags={"Class": "b"}),
        ],
        user_override="class-b",
    )

    assert completed.returncode == 0, completed.stderr
    assert "nodeClass=class-b" in completed.stdout
    assert "configure=false" in completed.stdout
    assert "tag=Class=b" in completed.stdout
    assert "tag=Class=a" not in completed.stdout
    assert not any(call.endswith("get nodepools -o json") for call in calls)
