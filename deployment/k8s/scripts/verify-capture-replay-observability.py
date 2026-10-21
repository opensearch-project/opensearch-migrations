#!/usr/bin/env python3

"""Smoke-test the dashboard resources installed by the Kubernetes Helm chart.

This check deliberately knows nothing about dashboard panels, queries, metrics,
collector policy, or alerting policy. Those contracts belong with the code and
tests that define them. The Gradle verification only proves that:

* Helm renders the embedded Grafana dashboard as valid JSON; and
* the EKS child installer renders valid ACK CloudWatch Dashboard resources with
  valid, substituted JSON bodies.
"""

import json
import re
import subprocess
import sys
from pathlib import Path


DASHBOARD_MANIFESTS = "/dashboards/dashboards.yaml"
# A single exec argument is limited to 128 KiB (MAX_ARG_STRLEN); keep the inlined Job scripts well under it.
MAX_SCRIPT_BYTES = 96 * 1024
UNRESOLVED_PLACEHOLDER = re.compile(
    r"placeholder-(?:region|stage|qualifier|account-id)"
)

# The lowest Kubernetes version the chart supports (Chart.yaml kubeVersion).
KUBE_VERSION = "1.35.0"


def fail(message):
    raise RuntimeError(message)


def parse_json(text, name):
    try:
        return json.loads(text)
    except json.JSONDecodeError as error:
        fail(f"{name} is malformed JSON: {error}")


def literal_blocks(text, key):
    """Extract YAML literal-block values without adding a YAML dependency."""
    return _literal_blocks(text, re.compile(r"^(\s*)" + re.escape(key) + r":\s*\|[-+]?\s*$"), key)


def script_blocks(text):
    """The literal-block list items (``- |``) that carry the rendered Job shell scripts."""
    return _literal_blocks(text, re.compile(r"^(\s*)- \|[-+]?\s*$"), "script")


def _literal_blocks(text, marker, key):
    lines = text.splitlines()
    blocks = []
    index = 0
    while index < len(lines):
        match = marker.match(lines[index])
        index += 1
        if match:
            block, index = _collect_block(lines, index, len(match.group(1)), key)
            blocks.append(block)
    return blocks


def _collect_block(lines, index, marker_indent, key):
    content_indent = None
    content = []
    while index < len(lines):
        line = lines[index]
        if line.strip():
            indentation = len(line) - len(line.lstrip(" "))
            if indentation <= marker_indent:
                break
            content_indent = content_indent if content_indent is not None else indentation
            if indentation < content_indent:
                fail(f"Rendered {key} block has inconsistent indentation")
            line = line[content_indent:]
        else:
            line = ""
        content.append(line)
        index += 1
    if content_indent is None:
        fail(f"Rendered {key} block is empty")
    return "\n".join(content).rstrip("\n"), index


def render(helm, chart, variant, *extra):
    result = subprocess.run(
        [
            helm,
            "template",
            f"capture-replay-dashboard-wiring-{variant}",
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


def mounted_dashboard_manifests(rendered):
    """The Dashboard CRs the installer applies, from the hook ConfigMap its Job mounts."""
    if not re.search(r"^\s*name:\s*\S+-cloudwatch-dashboard-manifests\s*$", rendered, re.MULTILINE):
        fail("EKS render is missing the cloudwatch-dashboard-manifests ConfigMap")
    if f"kubectl apply -f {DASHBOARD_MANIFESTS}" not in rendered:
        fail(f"EKS child installer does not apply {DASHBOARD_MANIFESTS}")
    if f"kubectl delete -f {DASHBOARD_MANIFESTS}" not in rendered:
        fail(f"EKS pre-delete cleanup does not delete {DASHBOARD_MANIFESTS}")
    manifests = literal_blocks(rendered, "dashboards.yaml")
    if len(manifests) != 1:
        fail(f"EKS render has {len(manifests)} dashboard manifest blocks; expected one")
    return manifests


def validate_script_sizes(rendered, variant):
    largest = max((len(block.encode()) for block in script_blocks(rendered)), default=0)
    if largest > MAX_SCRIPT_BYTES:
        fail(
            f"{variant} render has a {largest}-byte Job script; scripts are a single exec argument, "
            f"which Linux limits to 128 KiB, so keep them under {MAX_SCRIPT_BYTES} bytes"
        )


def dashboard_documents(artifacts):
    for artifact in artifacts:
        for document in re.split(r"^---\s*$", artifact, flags=re.MULTILINE):
            if (
                re.search(
                    r"^\s*apiVersion:\s*cloudwatch\.services\.k8s\.aws/",
                    document,
                    re.MULTILINE,
                ) and
                re.search(
                    r"^\s*kind:\s*Dashboard\s*$", document, re.MULTILINE
                )
            ):
                yield document


def dashboard_name(document):
    match = re.search(
        r'^\s*dashboardName:\s*"?([^"\n]+)"?\s*$',
        document,
        re.MULTILINE,
    )
    if not match:
        fail("ACK Dashboard resource is missing spec.dashboardName")
    return match.group(1).strip()


def dashboard_body(document, name):
    bodies = literal_blocks(document, "dashboardBody")
    if len(bodies) > 1:
        fail(f"{name} has multiple dashboard bodies")
    if bodies:
        value = parse_json(bodies[0], name)
    else:
        match = re.search(r"^\s*dashboardBody:\s*(.+)$", document, re.MULTILINE)
        if not match:
            fail(f"{name} is missing spec.dashboardBody")
        value = parse_json(match.group(1).strip(), f"{name} YAML scalar")
        if isinstance(value, str):
            value = parse_json(value, name)
    if not isinstance(value, dict):
        fail(f"{name} dashboardBody is not a JSON object")
    return value


def validate_grafana(rendered, variant):
    if not re.search(
        r"^\s*name:\s*capture-replay-observability-dashboard\s*$",
        rendered,
        re.MULTILINE,
    ):
        fail(f"{variant} render is missing the Grafana dashboard ConfigMap")
    blocks = literal_blocks(rendered, "capture-replay-observability.json")
    if len(blocks) != 1:
        fail(
            f"{variant} render has {len(blocks)} embedded capture/replay "
            "Grafana dashboards; expected one"
        )
    dashboard = parse_json(blocks[0], f"{variant} Grafana dashboard")
    if not isinstance(dashboard, dict):
        fail(f"{variant} Grafana dashboard is not a JSON object")


def validate_ack(rendered):
    dashboards = list(dashboard_documents(mounted_dashboard_manifests(rendered)))
    capture_replay = []
    for document in dashboards:
        name = dashboard_name(document)
        body = dashboard_body(document, f"ACK Dashboard {name}")
        if UNRESOLVED_PLACEHOLDER.search(json.dumps(body)):
            fail(f"ACK Dashboard {name} contains an unresolved chart placeholder")
        if name.endswith("-CaptureReplay"):
            capture_replay.append(name)
    if capture_replay != ["MA-verify-us-east-2-CaptureReplay"]:
        fail(
            "EKS render did not wire the capture/replay dashboard to the expected "
            f"ACK Dashboard resource: {capture_replay}"
        )
    return len(dashboards)


def main():
    args = sys.argv[1:]
    # The Gradle task passes the pinned Helm client it resolved; default to helm on the PATH.
    helm = "helm"
    if len(args) == 3 and args[1] == "--helm":
        helm = args[2]
    elif len(args) != 1:
        fail("Usage: verify-capture-replay-observability.py CHART_DIRECTORY [--helm HELM_EXECUTABLE]")
    chart = Path(args[0]).resolve()
    local = render(helm, chart, "local")
    eks = render(
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
    )
    validate_grafana(local, "local")
    validate_grafana(eks, "EKS")
    validate_script_sizes(local, "local")
    validate_script_sizes(eks, "EKS")
    ack_count = validate_ack(eks)
    print(
        "Dashboard wiring smoke test passed: "
        f"Grafana local=1 EKS=1; ACK dashboards={ack_count}"
    )


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
