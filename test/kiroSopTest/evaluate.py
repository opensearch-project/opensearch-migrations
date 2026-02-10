#!/usr/bin/env python3
"""Evaluate migration results after kiro-cli run."""
import argparse
import json
import logging
import subprocess
import sys

logging.basicConfig(format="%(asctime)s [%(levelname)s] %(message)s", level=logging.INFO)
log = logging.getLogger(__name__)


def run_bash(command: str, timeout: int = 300) -> dict:
    try:
        r = subprocess.run(["bash", "-lc", command], capture_output=True, text=True, timeout=timeout)
        return {"exit_code": r.returncode, "output": r.stdout + r.stderr}
    except Exception as e:
        return {"exit_code": 1, "output": str(e)}


def evaluate(namespace: str = "ma") -> dict:
    scores = {}

    # 1. Workflow Completion (40 pts)
    log.info("Evaluating workflow completion...")
    wf = run_bash(
        f"kubectl exec migration-console-0 -n {namespace} -- "
        f"bash -c 'source /.venv/bin/activate && workflow status --all' 2>&1"
    )
    if "Succeeded" in wf["output"]:
        scores["workflow_completion"] = 40
    elif "Running" in wf["output"]:
        scores["workflow_completion"] = 20
    else:
        scores["workflow_completion"] = 0
    log.info(f"  Workflow: {scores['workflow_completion']}/40")

    # 2. Data Integrity (30 pts)
    log.info("Evaluating data integrity...")
    src = run_bash(
        f"kubectl exec migration-console-0 -n {namespace} -- "
        f"bash -c 'source /.venv/bin/activate && "
        f"console clusters curl source_cluster /_cat/indices?format=json' 2>&1"
    )
    tgt = run_bash(
        f"kubectl exec migration-console-0 -n {namespace} -- "
        f"bash -c 'source /.venv/bin/activate && "
        f"console clusters curl target_cluster /_cat/indices?format=json' 2>&1"
    )
    try:
        src_indices = {
            i["index"]: int(i["docs.count"])
            for i in json.loads(src["output"])
            if not i["index"].startswith(".")
        }
        tgt_indices = {
            i["index"]: int(i["docs.count"])
            for i in json.loads(tgt["output"])
            if not i["index"].startswith(".")
        }
        if src_indices:
            matched = sum(1 for idx, cnt in src_indices.items() if tgt_indices.get(idx) == cnt)
            scores["data_integrity"] = round(matched / len(src_indices) * 30)
        else:
            scores["data_integrity"] = 0
    except Exception:
        scores["data_integrity"] = 0
    log.info(f"  Data: {scores['data_integrity']}/30")

    # 3. SOP Compliance (20 pts)
    log.info("Evaluating SOP compliance...")
    compliance = 0
    for artifact in ["plan.md", "run-log.md", "summary.md"]:
        check = run_bash(f"find .agents/migration/ -name '{artifact}' 2>/dev/null | head -1")
        if check["output"].strip():
            compliance += 5
    if run_bash("ls -la .agents/migration/ 2>/dev/null")["exit_code"] == 0:
        compliance += 5
    scores["sop_compliance"] = compliance
    log.info(f"  SOP Compliance: {compliance}/20")

    # 4. Efficiency (10 pts)
    scores["efficiency"] = 10 if scores["workflow_completion"] == 40 else 0
    log.info(f"  Efficiency: {scores['efficiency']}/10")

    scores["total"] = sum(scores.values())
    log.info(f"TOTAL SCORE: {scores['total']}/100")
    return scores


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--namespace", default="ma")
    p.add_argument("--output", default="sop-test-report.json")
    p.add_argument("--min-score", type=int, default=70)
    args = p.parse_args()

    scores = evaluate(args.namespace)
    report = {"scores": scores, "pass": scores["total"] >= args.min_score}

    with open(args.output, "w") as f:
        json.dump(report, f, indent=2)
    log.info(f"Report written to {args.output}")

    print(f"\n{'='*60}\nSOP TEST REPORT\n{'='*60}")
    for k, v in scores.items():
        print(f"  {k}: {v}")
    print(f"\n  PASS: {report['pass']} (min: {args.min_score})\n{'='*60}")

    sys.exit(0 if report["pass"] else 1)


if __name__ == "__main__":
    main()
