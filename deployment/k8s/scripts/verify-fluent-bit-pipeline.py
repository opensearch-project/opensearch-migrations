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
SERVICE_ACCOUNT_UID = re.compile(
    r'--set-string "podAnnotations\.migrations\\\\\.opensearch\\\\\.org/service-account-uid='
    r'\$\(kubectl get serviceaccount (\S+) -n ma -o jsonpath=\'\{\.metadata\.uid\}\'\)"')


def fail(message):
    raise RuntimeError(message)


def kubernetes_filter_lines(config):
    """The property lines of the [FILTER] section whose Name is kubernetes, or None."""
    lines = config.splitlines()
    for i, line in enumerate(lines):
        if line.split() == ["Name", "kubernetes"]:
            section = []
            for following in lines[i + 1:]:
                if not following.strip() or following.strip().startswith("["):
                    return section
                section.append(following.strip())
            return section
    return None


def service_account_name(values):
    """The name under the top-level serviceAccount key of the fluent-bit values, or None."""
    lines = [line.rstrip() for line in values.splitlines()]
    if "serviceAccount:" not in lines:
        return None
    for line in lines[lines.index("serviceAccount:") + 1:]:
        if line and not line[0].isspace():
            return None
        words = line.split()
        if len(words) == 2 and words[0] == "name:":
            return words[1]
    return None


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

    kubernetes_filter = kubernetes_filter_lines(values)
    if kubernetes_filter is None:
        fail(f"The {variant} fluent-bit configuration has no kubernetes filter")
    if ["Buffer_Size", "0"] not in [line.split() for line in kubernetes_filter]:
        fail(f"The {variant} kubernetes filter must set Buffer_Size 0, or large pod specs fail the metadata lookup")

    service_account = service_account_name(values)
    if not service_account:
        fail(f"The {variant} fluent-bit values name no service account")
    annotated = SERVICE_ACCOUNT_UID.findall(rendered)
    if annotated != [service_account]:
        fail(f"The {variant} fluent-bit install must annotate its pods with the UID of service account "
             f"{service_account!r}; found {annotated!r}")


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
