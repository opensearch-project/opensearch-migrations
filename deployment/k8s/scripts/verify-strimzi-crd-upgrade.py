#!/usr/bin/env python3

"""Check that the dependency installer migrates and applies the Strimzi CRDs before upgrading the operator.

Helm never upgrades CRDs. The rendered installer must run Strimzi's v1 API conversion tool and apply the
configured chart's crds/ directory (from `helm pull`, for both the Helm repository and a private-ECR OCI
reference) before `helm upgrade --install strimzi-kafka-operator`, and must skip all of it when Strimzi is off.
"""

import re
import subprocess
import sys
from pathlib import Path

OCI = "oci://123456789012.dkr.ecr.us-east-1.amazonaws.com/charts/strimzi-kafka-operator"
INSTALL = re.compile(r"helm upgrade --install \\\s*\n\s*strimzi-kafka-operator \\")
CONVERT = ("v1-api-conversion.sh convert-resource --all-namespaces && "
           "/opt/v1-api-conversion/bin/v1-api-conversion.sh crd-upgrade")
APPLY = "kubectl apply --server-side --force-conflicts -f /tmp/strimzi-chart/strimzi-kafka-operator/crds/"


def render(helm, chart, *extra):
    return subprocess.run(
        [helm, "template", "strimzi-crd-check", str(chart), "--namespace", "ma", "--kube-version", "1.35.0",
         "--show-only", "templates/childrenChartInstaller/installJob.yaml", *extra],
        check=True, stdout=subprocess.PIPE, text=True).stdout


def check(job, chart_ref, version, variant):
    install = INSTALL.search(job)
    pull = f"helm pull {chart_ref} --version {version} --untar --untardir /tmp/strimzi-chart"
    for fragment in (CONVERT, pull, APPLY):
        offset = job.find(fragment)
        if offset < 0 or not install or offset > install.start():
            raise RuntimeError(f"{variant}: `{fragment}` must run before the strimzi-kafka-operator upgrade")
    if "helm show crds" in job:
        raise RuntimeError(f"{variant}: `helm show crds` output loses document separators under Helm 3")


def main():
    chart, helm = Path(sys.argv[1]).resolve(), sys.argv[3] if len(sys.argv) == 4 else "helm"
    version = re.search(r"(?m)^ {2}strimzi-kafka-operator:\n {4}version: (\S+)", (chart / "values.yaml").read_text())
    check(render(helm, chart), "chart-repo-strimzi-kafka-operator/strimzi-kafka-operator", version[1], "repository")
    check(render(helm, chart, "--set-string", f"charts.strimzi-kafka-operator.repository={OCI}"), OCI, version[1],
          "private ECR")
    disabled = render(helm, chart, "--set", "conditionalPackageInstalls.strimzi-kafka-operator=false")
    if "strimzi_upgrade_crds" in disabled:
        raise RuntimeError("the Strimzi CRD upgrade runs although strimzi-kafka-operator is disabled")
    print(f"Strimzi CRD upgrade verified for strimzi-kafka-operator {version[1]}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, TypeError, subprocess.CalledProcessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
