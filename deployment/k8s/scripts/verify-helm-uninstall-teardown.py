#!/usr/bin/env python3

"""Validate the rendered pre-delete lifecycle for the Migration Assistant chart.

The Helm templates remain authoritative.  This check renders local and EKS
variants, verifies that runtime cleanup precedes child-chart uninstall, and
confirms that EKS install/delete operations consume the same mounted ACK
Dashboard manifest ConfigMap.  It intentionally does not duplicate Dashboard names or
child-chart inventories.
"""

import re
import subprocess
import sys
from pathlib import Path


DASHBOARD_MANIFESTS = "/dashboards/dashboards.yaml"
APPLY_MANIFESTS = re.compile(r"kubectl apply -f " + re.escape(DASHBOARD_MANIFESTS))
DELETE_MANIFESTS = re.compile(
    r"kubectl delete -f " + re.escape(DASHBOARD_MANIFESTS) + r" --ignore-not-found --wait=true --timeout=\d+s"
)
# Phase 1 keeps the S3 endpoint chart alive: the bucket-deletion hook runs after it, at a later weight.
PHASE_ONE_UNINSTALL = "uninstall_all_charts_except fluent-bit localstack"

# The lowest Kubernetes version the chart supports (Chart.yaml kubeVersion).
KUBE_VERSION = "1.35.0"


def rendered_documents(rendered):
    """Split a multi-document helm template render into its YAML documents."""
    return re.split(r"^---\s*$", rendered, flags=re.MULTILINE)


def fail(message):
    raise RuntimeError(message)


def render(helm, chart, variant, *extra):
    result = subprocess.run(
        [
            helm,
            "template",
            f"uninstall-verification-{variant}",
            str(chart),
            "--namespace",
            "ma",
            # Offline renders otherwise assume the client's built-in Kubernetes version, which for older Helm
            # releases predates the chart's kubeVersion constraint.
            "--kube-version",
            KUBE_VERSION,
            *extra,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode:
        fail(f"Failed to render {variant} Helm variant:\n{result.stdout}")
    return result.stdout


def pre_delete_job(rendered, variant):
    documents = rendered_documents(rendered)
    matches = [
        document
        for document in documents
        if 'name: uninstall-verification-' + variant + '-helm-uninstaller' in document and
        '"helm.sh/hook": pre-delete' in document
    ]
    if len(matches) != 1:
        fail(f"{variant} render has {len(matches)} pre-delete uninstall Jobs")
    return matches[0]


def named_pre_delete_job(rendered, name, variant):
    documents = rendered_documents(rendered)
    matches = [
        document
        for document in documents
        if re.search(rf"(?m)^\s*name:\s*{re.escape(name)}\s*$", document) and
        '"helm.sh/hook": pre-delete' in document
    ]
    if len(matches) != 1:
        fail(f"{variant} render has {len(matches)} pre-delete Jobs named {name}")
    return matches[0]


def named_pre_install_job(rendered, name):
    matches = [
        document
        for document in rendered_documents(rendered)
        if f"name: {name}" in document and re.search(r'"helm\.sh/hook":\s*pre-install', document)
    ]
    if len(matches) != 1:
        fail(f"Expected one rendered pre-install Job named {name}; found {len(matches)}")
    return matches[0]


def hook_weight(job, name):
    match = re.search(r'"helm\.sh/hook-weight":\s*"(-?\d+)"', job)
    if not match:
        fail(f"Rendered pre-delete Job {name} is missing a numeric hook weight")
    return int(match.group(1))


def function_body(script, name):
    match = re.search(
        rf"(?ms)^\s*{re.escape(name)}\(\) \{{\n(.*?)^\s*\}}$",
        script,
    )
    if not match:
        fail(f"Rendered uninstall Job is missing {name}()")
    return match.group(1)


def require_in_order(text, fragments, description):
    offset = 0
    for fragment in fragments:
        index = text.find(fragment, offset)
        if index < 0:
            fail(f"{description} is missing or misorders: {fragment}")
        offset = index + len(fragment)


def standalone_call_offset(script, name):
    match = re.search(rf"(?m)^\s*{re.escape(name)}\s*$", script)
    if not match:
        fail(f"Rendered uninstall Job does not invoke {name}")
    return match.start()


def manifest_config_map(rendered, variant):
    documents = [
        document
        for document in rendered_documents(rendered)
        if re.search(r"^kind:\s*ConfigMap\s*$", document, re.MULTILINE) and
        f"name: uninstall-verification-{variant}-cloudwatch-dashboard-manifests" in document
    ]
    if len(documents) != 1:
        fail(f"{variant} render has {len(documents)} Dashboard manifest ConfigMaps; expected one")
    return documents[0]


def mounts_dashboard_manifests(job, variant):
    return (
        f"name: uninstall-verification-{variant}-cloudwatch-dashboard-manifests" in job and
        "mountPath: /dashboards" in job
    )


def validate_runtime_cleanup(job, variant):
    if '{"spec":{"shutdown":"Stop"}}' not in job:
        fail(f"{variant} uninstall does not stop active Argo workflows")
    if "delete workflows.argoproj.io --all" not in job:
        fail(f"{variant} uninstall does not delete Argo workflows")
    reset_command = function_body(job, "run_workflow_reset")
    if not re.search(
        r"timeout\s+\d+\s+workflow reset --all --include-proxies "
        r"--delete-storage\s+\\?\s*--namespace \"\$RELEASE_NAMESPACE\"",
        reset_command,
        re.DOTALL,
    ):
        fail(f"{variant} uninstall workflow reset lacks a timeout or required arguments")
    reset_wrapper = function_body(job, "reset_migration_resources")
    if not re.search(
        r"retry\s+\d+\s+\d+\s+\"workflow reset\"\s+--\s+run_workflow_reset",
        reset_wrapper,
    ):
        fail(f"{variant} uninstall lacks a bounded, retried workflow reset")

    history_cleanup = function_body(job, "delete_migration_run_history")
    if "delete migrationruns.migrations.opensearch.org --all" not in history_cleanup:
        fail(f"{variant} uninstall does not delete immutable MigrationRun history")
    if not re.search(
        r"retry\s+\d+\s+\d+\s+\"delete MigrationRun history\"",
        history_cleanup,
    ):
        fail(f"{variant} MigrationRun cleanup is not retried")

    verification = function_body(job, "verify_no_migration_or_strimzi_resources")
    for group in (
        "migrations.opensearch.org",
        "kafka.strimzi.io",
        "core.strimzi.io",
    ):
        if group not in verification:
            fail(f"{variant} cleanup does not verify API group {group}")

    preparation = function_body(job, "prepare_release_for_uninstall")
    require_in_order(
        preparation,
        [
            "quiesce_and_delete_argo_workflows",
            "reset_migration_resources",
            "delete_migration_run_history",
            "verify_no_migration_or_strimzi_resources",
            "delete_release_cloudwatch_dashboards",
        ],
        f"{variant} pre-delete preparation",
    )
    prepare_offset = standalone_call_offset(job, "prepare_release_for_uninstall")
    uninstall_offset = standalone_call_offset(
        job, PHASE_ONE_UNINSTALL
    )
    if prepare_offset >= uninstall_offset:
        fail(f"{variant} pre-delete Job uninstalls child charts before runtime cleanup")


def validate_s3_cleanup(job, variant):
    if "s3 rm s3://" in job:
        fail(f"{variant} S3 cleanup still uses recursive per-object deletion")
    for fragment in (
        "list-objects-v2",
        "list-object-versions",
        "s3api delete-objects",
        "--max-keys 1000",
        "file://$BATCH_FILE",
    ):
        if fragment not in job:
            fail(f"{variant} S3 cleanup is missing batched-delete behavior: {fragment}")
    if job.index("list-object-versions") > job.index("list-objects-v2"):
        fail(
            f"{variant} S3 cleanup deletes current objects before retained versions, "
            "which creates avoidable delete markers"
        )


def validate_dashboard_manifest_reuse(rendered, jobs):
    if DELETE_MANIFESTS.search(jobs["local"]):
        fail("Local uninstall unexpectedly deletes ACK Dashboard resources")
    installer = named_pre_install_job(rendered["eks"], "uninstall-verification-eks-dependency-installer")
    if not APPLY_MANIFESTS.search(installer) or not DELETE_MANIFESTS.search(jobs["eks"]):
        fail(f"EKS install and uninstall must apply and delete the mounted {DASHBOARD_MANIFESTS}")
    for job, description in ((installer, "installer"), (jobs["eks"], "pre-delete cleanup")):
        if not mounts_dashboard_manifests(job, "eks"):
            fail(f"EKS {description} Job does not mount the Dashboard manifest ConfigMap")
    config_map = manifest_config_map(rendered["eks"], "eks")
    hooks = re.search(r'"helm\.sh/hook":\s*(\S+)', config_map)
    if not hooks or not {"pre-install", "pre-delete"} <= set(hooks.group(1).split(",")):
        fail("EKS Dashboard manifest ConfigMap must exist for both install and pre-delete hooks")
    weight = hook_weight(config_map, "Dashboard manifest ConfigMap")
    if weight >= min(hook_weight(installer, "installer"), hook_weight(jobs["eks"], "pre-delete cleanup")):
        fail("EKS Dashboard manifest ConfigMap is created after a Job that mounts it")


def main():
    args = sys.argv[1:]
    # The Gradle task passes the pinned Helm client it resolved; default to helm on the PATH.
    helm = "helm"
    if len(args) == 3 and args[1] == "--helm":
        helm = args[2]
    elif len(args) != 1:
        fail("Usage: verify-helm-uninstall-teardown.py CHART_DIRECTORY [--helm HELM_EXECUTABLE]")
    chart = Path(args[0]).resolve()

    rendered = {
        "local": render(helm, chart, "local"),
        "eks": render(
            helm,
            chart,
            "eks",
            "--values",
            str(chart / "valuesEks.yaml"),
            "--set-string",
            "stageName=verify",
            "--set-string",
            "aws.region=us-east-2",
            "--set-string",
            "aws.account=123456789012",
        ),
    }
    jobs = {
        variant: pre_delete_job(output, variant)
        for variant, output in rendered.items()
    }
    for variant, job in jobs.items():
        validate_runtime_cleanup(job, variant)
        bucket_job = named_pre_delete_job(
            rendered[variant],
            f"delete-s3-bucket-uninstall-verification-{variant}",
            variant,
        )
        if hook_weight(bucket_job, "S3 bucket cleanup") <= hook_weight(
            job, "runtime cleanup"
        ):
            fail(
                f"{variant} render empties the S3 bucket before capture/replay "
                "writers are stopped"
            )
        validate_s3_cleanup(bucket_job, variant)

    validate_dashboard_manifest_reuse(rendered, jobs)

    print("Validated local/EKS pre-delete teardown and ACK Dashboard manifest reuse")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
