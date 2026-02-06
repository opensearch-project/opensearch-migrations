#!/usr/bin/env python3
"""Bedrock agent runner that executes the migration SOP and evaluates results."""
import argparse
import json
import logging
import os
import subprocess
import sys
import time

import boto3

logging.basicConfig(format="%(asctime)s [%(levelname)s] %(message)s", level=logging.INFO)
log = logging.getLogger(__name__)

MAX_OUTPUT = 30000  # truncate command output to stay within context
MAX_TURNS = 200
TIMEOUT_MINUTES = 90


def run_bash(command: str, timeout: int = 300) -> dict:
    """Execute a bash command and return stdout/stderr."""
    log.info(f"EXEC: {command[:200]}")
    try:
        r = subprocess.run(
            ["bash", "-lc", command],
            capture_output=True, text=True, timeout=timeout
        )
        out = r.stdout + ("\nSTDERR:\n" + r.stderr if r.stderr else "")
        if len(out) > MAX_OUTPUT:
            out = out[:MAX_OUTPUT] + f"\n... [truncated, {len(out)} chars total]"
        return {"exit_code": r.returncode, "output": out}
    except subprocess.TimeoutExpired:
        return {"exit_code": 124, "output": f"Command timed out after {timeout}s"}
    except Exception as e:
        return {"exit_code": 1, "output": str(e)}


def load_file(path: str) -> str:
    with open(path) as f:
        return f.read()


def build_system_prompt(repo_root: str) -> str:
    sop = load_file(os.path.join(repo_root, "agent-sops/opensearch-migration-assistant-eks.sop.md"))
    steering_dir = os.path.join(repo_root, "kiro-cli/kiro-cli-config/steering")
    steering = ""
    for name in sorted(os.listdir(steering_dir)):
        if name.endswith(".md"):
            steering += f"\n\n--- {name} ---\n" + load_file(os.path.join(steering_dir, name))

    return f"""You are an expert OpenSearch Migration Assistant agent running in a CI/CD pipeline.
Your job is to execute a complete migration following the SOP below.

CRITICAL RULES:
- You are in fully automated mode. NEVER ask for confirmation. NEVER wait for user input.
- Execute all commands directly using the execute_bash tool.
- If a command fails, diagnose and retry with a fix. Do not give up.
- When the migration is complete, output EXACTLY the line: MIGRATION_COMPLETE
- If you cannot complete the migration, output EXACTLY: MIGRATION_FAILED: <reason>

CANONICAL SOP:
{sop}

REFERENCE DOCS:
{steering}"""


TOOLS = [
    {
        "toolSpec": {
            "name": "execute_bash",
            "description": "Execute a bash command on the CI worker. kubectl, aws, helm are available. "
                           "For migration console commands, use: kubectl exec migration-console-0 -n ma -- "
                           "bash -c 'source /.venv/bin/activate && <command>'",
            "inputSchema": {
                "json": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string", "description": "Bash command to execute"}
                    },
                    "required": ["command"]
                }
            }
        }
    }
]


def run_agent(model_id: str, system_prompt: str, user_prompt: str, region: str) -> list:
    """Run the Bedrock converse loop. Returns list of all messages."""
    client = boto3.client("bedrock-runtime", region_name=region)
    messages = [{"role": "user", "content": [{"text": user_prompt}]}]
    all_messages = list(messages)
    start = time.time()

    for turn in range(MAX_TURNS):
        elapsed = (time.time() - start) / 60
        if elapsed > TIMEOUT_MINUTES:
            log.warning(f"Agent timeout after {elapsed:.0f} minutes")
            break

        log.info(f"Turn {turn + 1}/{MAX_TURNS} ({elapsed:.1f}m elapsed)")
        try:
            resp = client.converse(
                modelId=model_id,
                system=[{"text": system_prompt}],
                messages=messages,
                toolConfig={"tools": TOOLS},
                inferenceConfig={"maxTokens": 4096}
            )
        except Exception as e:
            log.error(f"Bedrock API error: {e}")
            time.sleep(10)
            continue

        msg = resp["output"]["message"]
        messages.append(msg)
        all_messages.append(msg)

        # Check stop reason
        stop = resp.get("stopReason", "")
        if stop == "end_turn":
            text = "".join(b.get("text", "") for b in msg["content"])
            log.info(f"Agent end_turn: {text[:200]}")
            if "MIGRATION_COMPLETE" in text or "MIGRATION_FAILED" in text:
                break
            # Agent stopped but didn't signal completion - nudge it
            nudge = {"role": "user", "content": [{"text": "Continue. If migration is done, verify results and output MIGRATION_COMPLETE or MIGRATION_FAILED: <reason>."}]}
            messages.append(nudge)
            all_messages.append(nudge)
            continue

        if stop != "tool_use":
            log.info(f"Unexpected stop reason: {stop}")
            break

        # Process tool calls
        tool_results = []
        for block in msg["content"]:
            if "toolUse" not in block:
                continue
            tool = block["toolUse"]
            if tool["name"] == "execute_bash":
                cmd = tool["input"].get("command", "")
                result = run_bash(cmd)
                tool_results.append({
                    "toolResult": {
                        "toolUseId": tool["toolUseId"],
                        "content": [{"text": json.dumps(result)}]
                    }
                })

        if tool_results:
            result_msg = {"role": "user", "content": tool_results}
            messages.append(result_msg)
            all_messages.append(result_msg)

    return all_messages


def evaluate(namespace: str = "ma") -> dict:
    """Run evaluation checks and return a score report."""
    scores = {}

    # 1. Workflow Completion (40 pts)
    log.info("Evaluating workflow completion...")
    wf = run_bash(
        f"kubectl exec migration-console-0 -n {namespace} -- "
        f"bash -c 'source /.venv/bin/activate && workflow status --all' 2>&1"
    )
    wf_out = wf["output"]
    if "Succeeded" in wf_out:
        scores["workflow_completion"] = 40
    elif "Running" in wf_out:
        scores["workflow_completion"] = 20
    elif "Failed" in wf_out:
        scores["workflow_completion"] = 0
    else:
        scores["workflow_completion"] = 0
    log.info(f"  Workflow: {scores['workflow_completion']}/40")

    # 2. Data Integrity (30 pts)
    log.info("Evaluating data integrity...")
    src = run_bash(
        f"kubectl exec migration-console-0 -n {namespace} -- "
        f"bash -c 'source /.venv/bin/activate && console clusters curl source_cluster /_cat/indices?format=json' 2>&1"
    )
    tgt = run_bash(
        f"kubectl exec migration-console-0 -n {namespace} -- "
        f"bash -c 'source /.venv/bin/activate && console clusters curl target_cluster /_cat/indices?format=json' 2>&1"
    )
    try:
        src_indices = {i["index"]: int(i["docs.count"]) for i in json.loads(src["output"])
                       if not i["index"].startswith(".")}
        tgt_indices = {i["index"]: int(i["docs.count"]) for i in json.loads(tgt["output"])
                       if not i["index"].startswith(".")}
        if src_indices:
            matched = sum(1 for idx, cnt in src_indices.items()
                          if tgt_indices.get(idx) == cnt)
            ratio = matched / len(src_indices)
            scores["data_integrity"] = round(ratio * 30)
            log.info(f"  Data: {matched}/{len(src_indices)} indices match ({ratio:.0%})")
        else:
            scores["data_integrity"] = 0
            log.info("  Data: no source indices found")
    except Exception as e:
        scores["data_integrity"] = 0
        log.info(f"  Data: parse error - {e}")

    # 3. SOP Compliance (20 pts) - check if agent created artifacts and ran key steps
    log.info("Evaluating SOP compliance...")
    compliance = 0
    # Check if artifacts dir exists
    artifacts = run_bash("ls -la .agents/migration/ 2>/dev/null || echo 'NO_ARTIFACTS'")
    if "NO_ARTIFACTS" not in artifacts["output"]:
        compliance += 5
    # Check if plan.md exists
    plan = run_bash("find .agents/migration/ -name 'plan.md' 2>/dev/null | head -1")
    if plan["output"].strip():
        compliance += 5
    # Check if run-log.md exists
    runlog = run_bash("find .agents/migration/ -name 'run-log.md' 2>/dev/null | head -1")
    if runlog["output"].strip():
        compliance += 5
    # Check if summary.md exists
    summary = run_bash("find .agents/migration/ -name 'summary.md' 2>/dev/null | head -1")
    if summary["output"].strip():
        compliance += 5
    scores["sop_compliance"] = compliance
    log.info(f"  SOP Compliance: {compliance}/20")

    # 4. Efficiency (10 pts) - just check it completed at all
    scores["efficiency"] = 10 if scores["workflow_completion"] == 40 else 0
    log.info(f"  Efficiency: {scores['efficiency']}/10")

    scores["total"] = sum(scores.values())
    log.info(f"TOTAL SCORE: {scores['total']}/100")
    return scores


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model-id", default="us.anthropic.claude-sonnet-4-20250514-v1:0")
    p.add_argument("--region", default="us-east-1")
    p.add_argument("--repo-root", default=".")
    p.add_argument("--cluster-details-json", required=True, help="JSON with source/target cluster details")
    p.add_argument("--stage", required=True)
    p.add_argument("--source-version", default="ES_7.10")
    p.add_argument("--target-version", default="OS_2.19")
    p.add_argument("--namespace", default="ma")
    p.add_argument("--output", default="sop-test-report.json")
    p.add_argument("--min-score", type=int, default=70, help="Minimum passing score")
    args = p.parse_args()

    cluster_details = json.loads(args.cluster_details_json)
    source_ep = cluster_details["source"]["endpoint"]
    target_ep = cluster_details["target"]["endpoint"]

    system_prompt = build_system_prompt(args.repo_root)

    user_prompt = f"""Execute a complete OpenSearch migration with these parameters:

hands_on_level: auto
allow_destructive: true
namespace: {args.namespace}
ma_environment_mode: use_existing_stage
stage: {args.stage}
aws_region: {args.region}

source_cluster:
  endpoint: {source_ep}
  version: {args.source_version}
  auth: sigv4 (region: {args.region}, service: es)

target_cluster:
  endpoint: {target_ep}
  version: {args.target_version}
  auth: sigv4 (region: {args.region}, service: es)

ENVIRONMENT STATUS:
- EKS cluster is deployed and kubectl is configured (current context points to the MA EKS cluster)
- Helm chart is installed in namespace '{args.namespace}'
- migration-console-0 pod is running
- Source and target configmaps exist in namespace '{args.namespace}'
- You can skip SOP Step 0 (environment acquisition) - it's already done
- Start from Step 1 (Initialize Run Workspace) and proceed through all remaining steps

Begin the migration now. Do not ask any questions."""

    log.info("Starting SOP agent run...")
    log.info(f"Model: {args.model_id}")
    log.info(f"Source: {source_ep} ({args.source_version})")
    log.info(f"Target: {target_ep} ({args.target_version})")

    conversation = run_agent(args.model_id, system_prompt, user_prompt, args.region)

    # Save conversation log
    conv_log = []
    for msg in conversation:
        entry = {"role": msg.get("role", "?")}
        texts = []
        for b in msg.get("content", []):
            if isinstance(b, dict):
                if "text" in b:
                    texts.append(b["text"])
                elif "toolUse" in b:
                    texts.append(f"[TOOL: {b['toolUse']['name']}({json.dumps(b['toolUse']['input'])[:200]})]")
                elif "toolResult" in b:
                    texts.append(f"[RESULT: {json.dumps(b['toolResult']['content'])[:200]}]")
        entry["content"] = "\n".join(texts)
        conv_log.append(entry)

    log.info("Agent run complete. Running evaluation...")
    scores = evaluate(args.namespace)

    report = {
        "scores": scores,
        "model_id": args.model_id,
        "source_version": args.source_version,
        "target_version": args.target_version,
        "stage": args.stage,
        "conversation_turns": len(conversation),
        "pass": scores["total"] >= args.min_score,
    }

    with open(args.output, "w") as f:
        json.dump(report, f, indent=2)
    log.info(f"Report written to {args.output}")

    # Save full conversation log
    log_path = args.output.replace(".json", "-conversation.json")
    with open(log_path, "w") as f:
        json.dump(conv_log, f, indent=2)
    log.info(f"Conversation log written to {log_path}")

    # Print summary
    print("\n" + "=" * 60)
    print("SOP TEST REPORT")
    print("=" * 60)
    for k, v in scores.items():
        print(f"  {k}: {v}")
    print(f"\n  PASS: {report['pass']} (min: {args.min_score})")
    print("=" * 60)

    sys.exit(0 if report["pass"] else 1)


if __name__ == "__main__":
    main()
