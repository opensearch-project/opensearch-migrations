"""Exercise the CloudFormation event streamer without contacting AWS."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace


BOOTSTRAP = (
    Path(__file__).parents[3] / "deployment/k8s/aws/aws-bootstrap.sh"
)

# The stub splits events into pages of this size. Real DescribeStackEvents pages are larger;
# a small size keeps the fixtures short while still spanning several pages.
PAGE_SIZE = 3


def event(eid, minute, status="CREATE_IN_PROGRESS", reason=None):
    return {"EventId": eid, "Timestamp": f"2026-11-12T10:{minute:02d}:00Z", "ResourceStatus": status,
            "ResourceType": "AWS::EC2::VPC", "LogicalResourceId": eid, "ResourceStatusReason": reason}


# Events left on the stack by an earlier deploy, oldest first.
HISTORY = [event(f"old{i}", i, status="UPDATE_COMPLETE") for i in range(1, 6)]

# Stands in for the AWS CLI. It serves the events for the current poll newest first, split into pages the
# way DescribeStackEvents returns them. Like the real CLI, it follows every page unless --no-paginate is
# passed, and with `--output text` it applies --query to each page separately (with jmespath, the
# library the AWS CLI uses), printing rows the way `--output text` does. Each call's arguments are
# logged so the test can check them.
AWS_STUB = """
import json, os, sys, jmespath
args = sys.argv[1:]
with open(os.environ["CALLS_LOG"], "a") as f:
    f.write(json.dumps(args) + "\\n")
with open(os.environ["EVENTS_FIXTURE"]) as f:
    events_per_poll = json.load(f)
events = events_per_poll[min(int(os.environ["POLL"]), len(events_per_poll) - 1)]
newest_first = list(reversed(events))
page_size = int(os.environ["PAGE_SIZE"])
pages = [newest_first[i:i + page_size] for i in range(0, len(newest_first), page_size)] or [[]]
if "--no-paginate" in args:
    pages = pages[:1]
query = args[args.index("--query") + 1]
for page in pages:
    for row in jmespath.search(query, {"StackEvents": page}) or []:
        print("\\t".join("None" if v is None else str(v) for v in row))
"""


def stream_function():
    source = BOOTSTRAP.read_text()
    match = re.search(r"^  stream_cfn_events\(\) \{\n.*?^  \}", source, re.MULTILINE | re.DOTALL)
    assert match, "stream_cfn_events not found in aws-bootstrap.sh"
    return match.group()


def run_stream(tmp_path, events_per_poll, polls):
    """Run stream_cfn_events for `polls` polls. events_per_poll[n] (oldest first) is what poll n sees."""
    fixture = tmp_path / "events.json"
    fixture.write_text(json.dumps(events_per_poll))
    stub = tmp_path / "aws_stub.py"
    stub.write_text(AWS_STUB)
    calls_log = tmp_path / "calls.log"
    stub_err = tmp_path / "stub.err"
    tac_called = tmp_path / "tac_called"
    harness = f"""
set -uo pipefail
cfn_stack_name=MA
region=us-east-1
polls=0
# The script discards the CLI's stderr, so the stub's own errors go to a file the test reads.
aws() {{ POLL=$polls "{sys.executable}" "{stub}" "$@" 2>>"{stub_err}"; }}
# The streamer must not depend on GNU-only tools such as tac (absent on macOS).
tac() {{ touch "{tac_called}"; return 127; }}
sleep() {{ polls=$((polls + 1)); if [[ $polls -ge {polls} ]]; then exit 0; fi; }}
{stream_function()}
stream_cfn_events
"""
    env = {
        **os.environ,
        # The streamer's mktemp seen-file outlives the run (its RETURN trap does not fire when the loop is
        # stopped), so keep it under tmp_path.
        "TMPDIR": str(tmp_path),
        "EVENTS_FIXTURE": str(fixture),
        "CALLS_LOG": str(calls_log),
        "PAGE_SIZE": str(PAGE_SIZE),
    }
    result = subprocess.run(["bash", "-c", harness], capture_output=True, text=True, timeout=30, env=env)

    assert result.returncode == 0, result.stderr
    assert not tac_called.exists(), "stream_cfn_events called tac"
    assert not stub_err.exists() or stub_err.read_text() == "", stub_err.read_text()
    calls = [json.loads(line) for line in calls_log.read_text().splitlines()]
    assert len(calls) == polls, "expected one describe-stack-events call per poll"
    for args in calls:
        assert args[:2] == ["cloudformation", "describe-stack-events"], args
        assert args[args.index("--stack-name") + 1] == "MA", args
        assert args[args.index("--region") + 1] == "us-east-1", args
        assert args[args.index("--output") + 1] == "text", args
    return SimpleNamespace(lines=result.stdout.splitlines(), stdout=result.stdout)


def logical_ids(lines):
    return [line.split()[3] for line in lines]


def test_first_poll_prints_only_the_newest_page_oldest_first(tmp_path):
    events = HISTORY + [event("new1", 31, reason="User Initiated"), event("new2", 32)]
    run = run_stream(tmp_path, [events], polls=1)

    # 7 events span 3 pages. Only the newest page is fetched, and it prints in the order it happened.
    assert logical_ids(run.lines) == ["old5", "new1", "new2"]
    assert run.lines[1].endswith("new1  (User Initiated)")
    # A null reason (printed as "None" by --output text) is dropped.
    assert run.lines[2].rstrip().endswith("new2")
    assert "None" not in run.stdout


def test_later_polls_print_only_new_events_once_in_order(tmp_path):
    new = [event(f"new{i}", 30 + i) for i in range(1, 7)]
    events_per_poll = [
        HISTORY[-1:] + new[:1],
        HISTORY[-1:] + new[:3],
        HISTORY[-1:] + new[:3],  # nothing new on this poll
        HISTORY[-1:] + new[:6],
    ]
    run = run_stream(tmp_path, events_per_poll, polls=len(events_per_poll))

    assert logical_ids(run.lines) == ["old5", "new1", "new2", "new3", "new4", "new5", "new6"]
