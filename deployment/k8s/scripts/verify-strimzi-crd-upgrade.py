#!/usr/bin/env python3

"""Verify the Strimzi CRD upgrade that the dependency installer runs before upgrading the operator.

Helm never upgrades the CRDs in a chart's crds/ directory, and Strimzi 1.x CRDs drop the v1beta2
version that Strimzi 0.49-0.51 stored. This check renders the installer Job and proves that the CRD
upgrade runs before `helm upgrade --install strimzi-kafka-operator`, reads the CRDs of the configured
chart version from the same chart reference the install uses (Helm repository or private-ECR OCI), and
is left out when Strimzi is disabled. It then runs the rendered shell against a fake kubectl/helm to
check the fresh-install, v1beta2-stored, already-migrated and pre-0.49 paths.
"""

import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

# The lowest Kubernetes version the chart supports (Chart.yaml kubeVersion).
KUBE_VERSION = "1.35.0"
RELEASE = "strimzi-crd-verification"
OCI_REPOSITORY = "oci://123456789012.dkr.ecr.us-east-1.amazonaws.com/charts/strimzi-kafka-operator"
BLOCK_START = 'STRIMZI_CRDS="'
BLOCK_END = "strimzi_upgrade_crds || exit 1"
STRIMZI_INSTALL = re.compile(r"helm upgrade --install \\\s*\n\s*strimzi-kafka-operator \\")

# Stands in for kubectl and helm. FAKE_STATE selects what the cluster holds; every call is logged.
FAKE_CLI = r"""#!/bin/sh
echo "$(basename "$0") $*" >> "$FAKE_LOG"
cmd="$(basename "$0") $*"
case "$cmd" in
  "helm show crds "*) echo "kind: CustomResourceDefinition"; exit 0 ;;
  "kubectl get crd "*" -o jsonpath={.status.storedVersions[*]}")
    [ "$FAKE_STATE" = migrated ] && echo v1 || echo v1beta2; exit 0 ;;
  "kubectl get crd "*" -o jsonpath={.spec.versions[*].name}")
    [ "$FAKE_STATE" = legacy ] && echo "v1beta2 v1beta1" || echo "v1 v1beta2"; exit 0 ;;
  "kubectl get crd "*" -o json")
    echo '{"spec":{"versions":[{"name":"v1","storage":false},{"name":"v1beta2","storage":true}]}}'; exit 0 ;;
  "kubectl get crd "*) [ "$FAKE_STATE" = fresh ] && exit 1 || exit 0 ;;
  "kubectl get kafkas.v1.kafka.strimzi.io -A -o jsonpath={range .items[*]}{.metadata.namespace}"*)
    echo "ma default"; exit 0 ;;
  "kubectl get "*" -A -o jsonpath="*) exit 0 ;;
  "kubectl get kafkas.v1.kafka.strimzi.io -n ma default -o json --ignore-not-found")
    echo '{"kind":"Kafka"}'; exit 0 ;;
  "kubectl replace -f -") cat > /dev/null; exit 0 ;;
  "kubectl patch "*|"kubectl apply "*|"kubectl wait "*) exit 0 ;;
esac
echo "unexpected call: $cmd" >&2
exit 1
"""


def fail(message):
    raise RuntimeError(message)


def render(helm, chart, *extra):
    result = subprocess.run(
        [helm, "template", RELEASE, str(chart), "--namespace", "ma", "--kube-version", KUBE_VERSION,
         "--show-only", "templates/childrenChartInstaller/installJob.yaml", *extra],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode:
        fail(f"Failed to render the dependency installer:\n{result.stdout}")
    return result.stdout


def strimzi_chart_version(chart):
    values = (chart / "values.yaml").read_text()
    match = re.search(r"(?ms)^  strimzi-kafka-operator:\n.*?^    version:\s*\"?([0-9][^\"\s]*)\"?\s*$", values)
    if not match:
        fail("values.yaml does not pin charts.strimzi-kafka-operator.version")
    return match.group(1)


def upgrade_block(job):
    lines = job.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if BLOCK_START in line)
        end = next(i for i, line in enumerate(lines) if BLOCK_END in line)
    except StopIteration:
        fail("The dependency installer does not run the Strimzi CRD upgrade")
    indent = len(lines[start]) - len(lines[start].lstrip())
    return "\n".join(line[indent:] for line in lines[start:end + 1]) + "\n"


def validate_rendered_job(job, chart_ref, version, variant):
    block_offset = job.find(BLOCK_END)
    install = STRIMZI_INSTALL.search(job)
    if block_offset < 0 or not install:
        fail(f"{variant}: the installer lacks the Strimzi CRD upgrade or the Strimzi chart install")
    if block_offset > install.start():
        fail(f"{variant}: the Strimzi CRD upgrade runs after the operator chart is upgraded")
    show = f"helm show crds {chart_ref} --version {version}"
    if show not in job:
        fail(f"{variant}: the CRD upgrade does not read the CRDs with `{show}`")


def run_block(block, state):
    with tempfile.TemporaryDirectory() as directory:
        bin_dir = Path(directory)
        for tool in ("kubectl", "helm"):
            path = bin_dir / tool
            path.write_text(FAKE_CLI)
            path.chmod(path.stat().st_mode | stat.S_IEXEC)
        log = bin_dir / "calls.log"
        log.touch()
        # The installer defines retry(); a single attempt keeps failures fast and visible here.
        script = 'retry() { shift 3; [ "$1" = "--" ] && shift; "$@"; }\n' + block
        result = subprocess.run(
            ["sh", "-c", script],
            env={**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "FAKE_STATE": state,
                 "FAKE_LOG": str(log)},
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            cwd=directory,
        )
        return result.returncode, result.stdout, log.read_text().splitlines()


def calls_matching(calls, pattern):
    return [index for index, call in enumerate(calls) if re.search(pattern, call)]


def validate_behaviour(block):
    code, output, calls = run_block(block, "fresh")
    if code or any(call.startswith(("helm", "kubectl apply", "kubectl patch")) for call in calls):
        fail(f"A fresh install must leave CRD creation to helm:\n{output}\n{calls}")

    code, output, calls = run_block(block, "stored-v1beta2")
    if code:
        fail(f"The v1beta2-stored upgrade failed:\n{output}")
    storage = calls_matching(calls, r"^kubectl patch crd kafkas\.kafka\.strimzi\.io --type=json -p ")
    rewrite = calls_matching(calls, r"^kubectl replace -f -$")
    status = calls_matching(calls, r"^kubectl patch crd kafkas\.kafka\.strimzi\.io --subresource=status ")
    apply = calls_matching(calls, r"^kubectl apply --server-side --force-conflicts ")
    if not (storage and rewrite and status and apply) or not storage[0] < rewrite[0] < status[0] < apply[0]:
        fail(f"The CRD upgrade must set v1 storage, rewrite resources, drop old stored versions, then apply "
             f"the new CRDs, in that order:\n{calls}")
    patch = json.loads(calls[storage[0]].split(" -p ", 1)[1])
    expected = [
        {"op": "replace", "path": "/spec/versions/0/served", "value": True},
        {"op": "replace", "path": "/spec/versions/0/storage", "value": True},
        {"op": "replace", "path": "/spec/versions/1/served", "value": True},
        {"op": "replace", "path": "/spec/versions/1/storage", "value": False},
    ]
    if patch != expected:
        fail(f"Unexpected storage-version patch: {patch}")

    code, output, calls = run_block(block, "migrated")
    if code or calls_matching(calls, r"^kubectl (patch|replace)") or not calls_matching(calls, r"^kubectl apply "):
        fail(f"An already-migrated install must only re-apply the CRDs:\n{output}\n{calls}")

    code, output, calls = run_block(block, "legacy")
    if not code or "older than 0.49" not in output or calls_matching(calls, r"^kubectl (patch|apply)"):
        fail(f"CRDs without the v1 API must stop the upgrade before changing anything:\n{output}\n{calls}")


def main():
    args = sys.argv[1:]
    # The Gradle task passes the pinned Helm client it resolved; default to helm on the PATH.
    helm = "helm"
    if len(args) == 3 and args[1] == "--helm":
        helm = args[2]
    elif len(args) != 1:
        fail("Usage: verify-strimzi-crd-upgrade.py CHART_DIRECTORY [--helm HELM_EXECUTABLE]")
    chart = Path(args[0]).resolve()
    version = strimzi_chart_version(chart)

    repo_job = render(helm, chart)
    validate_rendered_job(repo_job, "chart-repo-strimzi-kafka-operator/strimzi-kafka-operator", version,
                          "Helm repository")
    oci_job = render(helm, chart, "--set-string", f"charts.strimzi-kafka-operator.repository={OCI_REPOSITORY}")
    validate_rendered_job(oci_job, OCI_REPOSITORY, version, "private ECR")

    disabled = render(helm, chart, "--set", "conditionalPackageInstalls.strimzi-kafka-operator=false")
    if BLOCK_END in disabled:
        fail("The Strimzi CRD upgrade runs even though strimzi-kafka-operator is disabled")

    validate_behaviour(upgrade_block(repo_job))
    print(f"Strimzi CRD upgrade verified for strimzi-kafka-operator {version}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
