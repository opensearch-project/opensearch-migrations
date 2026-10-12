#!/usr/bin/env python3

"""Smoke-test the dashboard resources installed by the Kubernetes Helm chart.

This check deliberately knows nothing about dashboard panels, queries, metrics,
collector policy, or alerting policy. Those contracts belong with the code and
tests that define them. The Gradle verification only proves that:

* Helm renders the embedded Grafana dashboard as valid JSON; and
* the EKS child installer renders valid ACK CloudWatch Dashboard resources with
  valid, substituted JSON bodies.
"""

import base64
import json
import re
import subprocess
import sys
from pathlib import Path


BASE64_APPLY = re.compile(
    r"^\s*echo '([A-Za-z0-9+/=]+)' \| base64 -d \| kubectl apply -f -\s*$",
    re.MULTILINE,
)
UNRESOLVED_PLACEHOLDER = re.compile(
    r"placeholder-(?:region|stage|qualifier|account-id)"
)


def fail(message):
    raise RuntimeError(message)


def parse_json(text, name):
    try:
        return json.loads(text)
    except json.JSONDecodeError as error:
        fail(f"{name} is malformed JSON: {error}")


def literal_blocks(text, key):
    """Extract YAML literal-block values without adding a YAML dependency."""
    marker = re.compile(r"^(\s*)" + re.escape(key) + r":\s*\|[-+]?\s*$")
    lines = text.splitlines()
    blocks = []
    index = 0
    while index < len(lines):
        match = marker.match(lines[index])
        index += 1
        if not match:
            continue
        marker_indent = len(match.group(1))
        content_indent = None
        content = []
        while index < len(lines):
            line = lines[index]
            if not line.strip():
                content.append("")
                index += 1
                continue
            indentation = len(line) - len(line.lstrip(" "))
            if indentation <= marker_indent:
                break
            content_indent = content_indent if content_indent is not None else indentation
            if indentation < content_indent:
                fail(f"Rendered {key} block has inconsistent indentation")
            content.append(line[content_indent:])
            index += 1
        if content_indent is None:
            fail(f"Rendered {key} block is empty")
        blocks.append("\n".join(content).rstrip("\n"))
    return blocks


def render(chart, variant, *extra):
    result = subprocess.run(
        [
            "helm",
            "template",
            f"capture-replay-dashboard-wiring-{variant}",
            str(chart),
            "--namespace",
            "ma",
            *extra,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode:
        fail(f"Failed to render {variant} Helm variant:\n{result.stdout}")
    return result.stdout


def apply_payloads(rendered):
    payloads = []
    for encoded in BASE64_APPLY.findall(rendered):
        try:
            payloads.append(base64.b64decode(encoded, validate=True).decode())
        except (ValueError, UnicodeDecodeError) as error:
            fail(f"EKS render has an invalid base64 apply payload: {error}")
    return payloads


def dashboard_documents(artifacts):
    for artifact in artifacts:
        for document in re.split(r"^---\s*$", artifact, flags=re.MULTILINE):
            if (
                re.search(
                    r"^\s*apiVersion:\s*cloudwatch\.services\.k8s\.aws/",
                    document,
                    re.MULTILINE,
                )
                and re.search(
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
    payloads = apply_payloads(rendered)
    if not payloads:
        fail("EKS render does not apply any manifests through the child installer")
    dashboards = list(dashboard_documents([rendered, *payloads]))
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
    if len(sys.argv) != 2:
        fail("Usage: verify-capture-replay-observability.py CHART_DIRECTORY")
    chart = Path(sys.argv[1]).resolve()
    local = render(chart, "local")
    eks = render(
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
