#!/usr/bin/env python3

"""Verify the workload zone-spread policy the Helm chart publishes to the migration workflows.

The workflow templates read provider-config's workloadZoneSpread key when they create Kafka node
pools, capture proxies and replayers. This check renders the chart and proves that the default is
"preferred" (schedulable on clusters without zone labels), that the EKS values select "required",
and that an unsupported value fails the render instead of reaching the workflows.
"""

import re
import subprocess
import sys
from pathlib import Path

# The lowest Kubernetes version the chart supports (Chart.yaml kubeVersion).
KUBE_VERSION = "1.35.0"
EKS_SETTINGS = [
    "--set-string", "stageName=verify",
    "--set-string", "aws.region=us-east-2",
    "--set-string", "aws.account=123456789012",
]
ZONE_SPREAD = re.compile(r'^\s*workloadZoneSpread:\s*"([^"]*)"\s*$', re.MULTILINE)


def fail(message):
    raise RuntimeError(message)


def render(helm, chart, *extra):
    return subprocess.run(
        [helm, "template", "zone-spread-verification", str(chart), "--namespace", "ma",
         "--kube-version", KUBE_VERSION, *extra],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )


def published_zone_spread(helm, chart, variant, *extra):
    result = render(helm, chart, *extra)
    if result.returncode:
        fail(f"Failed to render the {variant} chart:\n{result.stdout}")
    values = ZONE_SPREAD.findall(result.stdout)
    if len(values) != 1:
        fail(f"The {variant} render publishes {len(values)} workloadZoneSpread values; expected one")
    return values[0]


def main():
    args = sys.argv[1:]
    # The Gradle task passes the pinned Helm client it resolved; default to helm on the PATH.
    helm = "helm"
    if len(args) == 3 and args[1] == "--helm":
        helm = args[2]
    elif len(args) != 1:
        fail("Usage: verify-workload-zone-spread.py CHART_DIRECTORY [--helm HELM_EXECUTABLE]")
    chart = Path(args[0]).resolve()

    for variant, extra, expected in (
        ("default", (), "preferred"),
        ("EKS", ("--values", str(chart / "valuesEks.yaml"), *EKS_SETTINGS), "required"),
    ):
        published = published_zone_spread(helm, chart, variant, *extra)
        if published != expected:
            fail(f"The {variant} chart publishes workloadZoneSpread={published!r}; expected {expected!r}")

    invalid = render(helm, chart, "--set", "workloadsNodePool.zoneSpread=sometimes")
    if invalid.returncode == 0 or "workloadsNodePool.zoneSpread must be" not in invalid.stdout:
        fail("An unsupported workloadsNodePool.zoneSpread value must fail the chart render")

    print("Validated workload zone spread: default=preferred, EKS=required, invalid values rejected")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
