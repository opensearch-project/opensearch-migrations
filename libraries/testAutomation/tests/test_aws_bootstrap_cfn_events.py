"""Exercise the CloudFormation event streamer without contacting AWS."""

import json
import re
import subprocess
import sys
from pathlib import Path


BOOTSTRAP = (
    Path(__file__).parents[3] / "deployment/k8s/aws/aws-bootstrap.sh"
)

# DescribeStackEvents returns the newest event first.
EVENTS_NEWEST_FIRST = [
    {"EventId": "e3", "Timestamp": "2026-11-12T10:00:03Z", "ResourceStatus": "CREATE_COMPLETE",
     "ResourceType": "AWS::CloudFormation::Stack", "LogicalResourceId": "MA", "ResourceStatusReason": None},
    {"EventId": "e2", "Timestamp": "2026-11-12T10:00:02Z", "ResourceStatus": "CREATE_IN_PROGRESS",
     "ResourceType": "AWS::EKS::Cluster", "LogicalResourceId": "Cluster",
     "ResourceStatusReason": "Resource creation Initiated"},
    {"EventId": "e1", "Timestamp": "2026-11-12T10:00:01Z", "ResourceStatus": "CREATE_IN_PROGRESS",
     "ResourceType": "AWS::CloudFormation::Stack", "LogicalResourceId": "MA",
     "ResourceStatusReason": "User Initiated"},
]

# Stands in for the AWS CLI: applies the real --query to the fixture with jmespath (the
# library the AWS CLI uses) and prints the rows the way `--output text` does.
AWS_STUB = """
import json, os, sys, jmespath
args = sys.argv[1:]
assert args[:2] == ["cloudformation", "describe-stack-events"], args
assert args[args.index("--output") + 1] == "text", args
with open(os.environ["EVENTS_FIXTURE"]) as f:
    events = json.load(f)
for row in jmespath.search(args[args.index("--query") + 1], {"StackEvents": events}):
    print("\\t".join("None" if v is None else str(v) for v in row))
"""


def stream_function():
    source = BOOTSTRAP.read_text()
    match = re.search(r"^  stream_cfn_events\(\) \{\n.*?^  \}", source, re.MULTILINE | re.DOTALL)
    assert match, "stream_cfn_events not found in aws-bootstrap.sh"
    return match.group()


def run_stream(tmp_path, polls=1):
    fixture = tmp_path / "events.json"
    fixture.write_text(json.dumps(EVENTS_NEWEST_FIRST))
    stub = tmp_path / "aws_stub.py"
    stub.write_text(AWS_STUB)
    harness = f"""
set -uo pipefail
cfn_stack_name=MA
region=us-east-1
polls=0
aws() {{ EVENTS_FIXTURE="{fixture}" "{sys.executable}" "{stub}" "$@"; }}
# The streamer must not depend on GNU-only tools such as tac (absent on macOS).
tac() {{ echo "tac is not available" >&2; return 127; }}
sleep() {{ polls=$((polls + 1)); if [[ $polls -ge {polls} ]]; then exit 0; fi; }}
{stream_function()}
stream_cfn_events
"""
    return subprocess.run(["bash", "-c", harness], capture_output=True, text=True, timeout=30)


def test_stream_prints_events_oldest_first_without_tac(tmp_path):
    result = run_stream(tmp_path)

    assert result.returncode == 0, result.stderr
    assert "tac" not in result.stderr
    lines = result.stdout.splitlines()
    assert [line.split()[0] for line in lines] == [
        "2026-11-12T10:00:01Z",
        "2026-11-12T10:00:02Z",
        "2026-11-12T10:00:03Z",
    ]
    assert lines[0].endswith("(User Initiated)")
    # A null reason (printed as "None" by --output text) is dropped.
    assert lines[2].rstrip().endswith("MA")
    assert "None" not in result.stdout


def test_stream_prints_each_event_once_across_polls(tmp_path):
    result = run_stream(tmp_path, polls=3)

    assert result.returncode == 0, result.stderr
    assert len(result.stdout.splitlines()) == len(EVENTS_NEWEST_FIRST)
