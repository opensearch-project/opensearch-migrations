#!/usr/bin/env python3

"""Verify that the chart's fluent-bit pipeline can look up Kubernetes metadata for every pod.

fluent-bit's kubernetes filter enriches each record before rewrite_tag re-tags it for the outputs; a record
whose lookup fails is never re-tagged and is dropped without an error. Two chart settings prevent that: an
unlimited Buffer_Size, because the installer job's pod spec exceeds the 32k default, and a pod annotation
carrying the UID of fluent-bit's service account, because every upgrade recreates that hook-managed account
and the running pods' tokens then fail with 401 until the DaemonSet rolls. This check renders the chart with
each provider's values and proves both are in place.
"""

import base64
import re
import subprocess
import sys
from pathlib import Path

# The lowest Kubernetes version the chart supports (Chart.yaml kubeVersion).
KUBE_VERSION = "1.35.0"
PROVIDERS = {
    "default": [],
    "EKS": ["--values", "valuesEks.yaml", "--set-string", "stageName=verify", "--set-string", "aws.region=us-east-2",
            "--set-string", "aws.account=123456789012", "--set-string", "aws.eksClusterName=verify-cluster"],
    "GKE": ["--values", "valuesGke.yaml", "--set-string", "gcp.project=verify-project",
            "--set-string", "gcp.clusterName=verify-cluster", "--set-string", "gcp.clusterLocation=us-central1"],
    "AKS": ["--values", "valuesAks.yaml", "--set-string", "azure.workloadIdentityClientId=verify-client",
            "--set-string", "azureStorageConfiguration.accountName=verifyaccount"],
    "local": ["--values", "valuesForLocalK8s.yaml"],
}
FLUENT_BIT_VALUES = re.compile(
    r'VALUES_B64="([A-Za-z0-9+/=]+)"\s*\n\s*echo "\$VALUES_B64" \| base64 -d > /tmp/values-fluent-bit\.yaml')
KUBERNETES_FILTER = re.compile(r'\[FILTER\]\s*\n\s*Name\s+kubernetes\s*\n((?:[ \t]*(?:#.*|\w+\s+.*)\n)+)')
SERVICE_ACCOUNT_UID = re.compile(
    r'--set-string "podAnnotations\.migrations\\\\\.opensearch\\\\\.org/service-account-uid='
    r'\$\(kubectl get serviceaccount (\S+) -n ma -o jsonpath=\'\{\.metadata\.uid\}\'\)"')


def fail(message):
    raise RuntimeError(message)


def render(helm, chart, variant, extra):
    args = [str(chart / a) if a.endswith(".yaml") else a for a in extra]
    result = subprocess.run(
        [helm, "template", "fluent-bit-verification", str(chart), "--namespace", "ma",
         "--kube-version", KUBE_VERSION, *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode:
        fail(f"Failed to render the {variant} chart:\n{result.stdout}")
    return result.stdout


def verify(helm, chart, variant, extra):
    rendered = render(helm, chart, variant, extra)
    encoded = FLUENT_BIT_VALUES.findall(rendered)
    if len(encoded) != 1:
        fail(f"The {variant} render installs fluent-bit {len(encoded)} times; expected once")
    values = base64.b64decode(encoded[0]).decode()

    kubernetes_filter = KUBERNETES_FILTER.search(values)
    if not kubernetes_filter:
        fail(f"The {variant} fluent-bit configuration has no kubernetes filter")
    if not re.search(r'^\s*Buffer_Size\s+0\s*$', kubernetes_filter.group(1), re.MULTILINE):
        fail(f"The {variant} kubernetes filter must set Buffer_Size 0, or large pod specs fail the metadata lookup")

    service_account = re.search(r'^serviceAccount:\s*\n(?:\s+.*\n)*?\s+name:\s*(\S+)', values, re.MULTILINE)
    if not service_account:
        fail(f"The {variant} fluent-bit values name no service account")
    annotated = SERVICE_ACCOUNT_UID.findall(rendered)
    if annotated != [service_account.group(1)]:
        fail(f"The {variant} fluent-bit install must annotate its pods with the UID of service account "
             f"{service_account.group(1)!r}; found {annotated!r}")


def main():
    args = sys.argv[1:]
    # The Gradle task passes the pinned Helm client it resolved; default to helm on the PATH.
    helm = "helm"
    if len(args) == 3 and args[1] == "--helm":
        helm = args[2]
    elif len(args) != 1:
        fail("Usage: verify-fluent-bit-pipeline.py CHART_DIRECTORY [--helm HELM_EXECUTABLE]")
    chart = Path(args[0]).resolve()

    for variant, extra in PROVIDERS.items():
        verify(helm, chart, variant, extra)

    print("Validated fluent-bit metadata lookups for " + ", ".join(PROVIDERS) +
          ": unlimited Buffer_Size and a service-account UID annotation that rolls the DaemonSet on upgrade")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
