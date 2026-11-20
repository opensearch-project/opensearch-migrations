#!/usr/bin/env python3

"""Verify the Azure Monitor log shipping the Helm chart configures for AKS.

valuesAksMonitor.yaml replaces the fluent-bit file output with azure_logs_ingestion, which reads its
destination from the azure-metadata ConfigMap and its client secret from a Secret. This check renders
the chart and proves that the AKS values alone keep the file output and render no azure-metadata,
that the overlay renders azure-metadata with every key the fluent-bit env reads, and that a missing
destination value fails the render instead of reaching fluent-bit.
"""

import base64
import re
import subprocess
import sys
from pathlib import Path

# The lowest Kubernetes version the chart supports (Chart.yaml kubeVersion).
KUBE_VERSION = "1.35.0"
AKS_SETTINGS = [
    "--set-string", "azure.workloadIdentityClientId=00000000-0000-0000-0000-000000000000",
    "--set-string", "azureStorageConfiguration.accountName=verifyaccount",
]
MONITOR_SETTINGS = {
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "clientId": "22222222-2222-2222-2222-222222222222",
    "dceUrl": "https://verify-dce.eastus-1.ingest.monitor.azure.com",
    "dcrImmutableId": "dcr-00000000000000000000000000000000",
    "tableName": "MigrationAssistant_CL",
}
FLUENT_BIT_VALUES = re.compile(
    r'VALUES_B64="([A-Za-z0-9+/=]+)"\s*\n\s*echo "\$VALUES_B64" \| base64 -d > /tmp/values-fluent-bit\.yaml')
AZURE_METADATA = re.compile(r'^kind: ConfigMap\nmetadata:\n  name: azure-metadata\n(?:.*\n)*?data:\n((?:  .*\n)+)',
                            re.MULTILINE)
CONFIG_MAP_KEYS = re.compile(r'configMapKeyRef:\s*\n\s*key: (\S+)\s*\n\s*name: azure-metadata')


def fail(message):
    raise RuntimeError(message)


def render(helm, chart, *extra):
    return subprocess.run(
        [helm, "template", "azure-monitor-verification", str(chart), "--namespace", "ma",
         "--kube-version", KUBE_VERSION, "--values", str(chart / "valuesAks.yaml"), *AKS_SETTINGS, *extra],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )


def rendered(helm, chart, variant, *extra):
    result = render(helm, chart, *extra)
    if result.returncode:
        fail(f"Failed to render the {variant} chart:\n{result.stdout}")
    return result.stdout


def fluent_bit_values(output, variant):
    encoded = FLUENT_BIT_VALUES.findall(output)
    if len(encoded) != 1:
        fail(f"The {variant} render installs fluent-bit {len(encoded)} times; expected once")
    return base64.b64decode(encoded[0]).decode()


def monitor_args(chart, *, without=None):
    args = ["--values", str(chart / "valuesAksMonitor.yaml")]
    for key, value in MONITOR_SETTINGS.items():
        if key != without:
            args += ["--set-string", f"azure.monitor.{key}={value}"]
    return args


def main():
    args = sys.argv[1:]
    # The Gradle task passes the pinned Helm client it resolved; default to helm on the PATH.
    helm = "helm"
    if len(args) == 3 and args[1] == "--helm":
        helm = args[2]
    elif len(args) != 1:
        fail("Usage: verify-azure-monitor-logs.py CHART_DIRECTORY [--helm HELM_EXECUTABLE]")
    chart = Path(args[0]).resolve()

    aks = rendered(helm, chart, "AKS")
    if AZURE_METADATA.search(aks):
        fail("The AKS chart without valuesAksMonitor.yaml renders azure-metadata")
    aks_fluent_bit = fluent_bit_values(aks, "AKS")
    if "azure_logs_ingestion" in aks_fluent_bit or "Name               file" not in aks_fluent_bit:
        fail("The AKS chart without valuesAksMonitor.yaml must keep the fluent-bit file output")

    shipping = rendered(helm, chart, "AKS monitor", *monitor_args(chart))
    metadata = AZURE_METADATA.search(shipping)
    if not metadata:
        fail("The AKS monitor chart does not render azure-metadata")
    data = dict(re.findall(r'^  (\w+): "([^"]*)"$', metadata.group(1), re.MULTILINE))
    expected = {
        "TENANT_ID": MONITOR_SETTINGS["tenantId"],
        "CLIENT_ID": MONITOR_SETTINGS["clientId"],
        "DCE_URL": MONITOR_SETTINGS["dceUrl"],
        "DCR_IMMUTABLE_ID": MONITOR_SETTINGS["dcrImmutableId"],
        "TABLE_NAME": MONITOR_SETTINGS["tableName"],
    }
    if data != expected:
        fail(f"azure-metadata holds {data!r}; expected {expected!r}")

    shipping_fluent_bit = fluent_bit_values(shipping, "AKS monitor")
    if "Name                  azure_logs_ingestion" not in shipping_fluent_bit:
        fail("The AKS monitor chart does not configure the azure_logs_ingestion output")
    if not re.search(r'Name\s+azure_logs_ingestion\n\s+Match\s+fluentbit-\*\n\s+log_level\s+warn\n',
                     shipping_fluent_bit):
        fail("The azure_logs_ingestion output must log at warn, or its per-upload info lines are shipped back")
    if "Name               file" in shipping_fluent_bit:
        fail("The AKS monitor chart still configures the fluent-bit file output")
    read_keys = set(CONFIG_MAP_KEYS.findall(shipping_fluent_bit))
    if read_keys != set(expected):
        fail(f"fluent-bit reads azure-metadata keys {sorted(read_keys)}; the ConfigMap holds {sorted(expected)}")
    if not re.search(r'secretKeyRef:\s*\n\s*key: clientSecret\s*\n\s*name: azure-monitor-logs-credentials',
                     shipping_fluent_bit):
        fail("fluent-bit does not read clientSecret from the azure-monitor-logs-credentials Secret")
    if re.search(r'^kind: PersistentVolumeClaim\n(?:.*\n)*?  name: logs-pvc$', shipping, re.MULTILINE):
        fail("The AKS monitor chart still renders the shared logs volume")

    for key in MONITOR_SETTINGS:
        missing = render(helm, chart, *monitor_args(chart, without=key))
        if missing.returncode == 0 or f"Missing required value: .Values.azure.monitor.{key}" not in missing.stdout:
            fail(f"A missing azure.monitor.{key} must fail the AKS monitor chart render")

    print("Validated Azure Monitor logs: AKS keeps the file output, valuesAksMonitor.yaml ships to "
          "azure_logs_ingestion with every azure-metadata key and the client secret Secret, missing values rejected")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
