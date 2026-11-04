#!/usr/bin/env python3
"""Checks the EKS Auto Mode NodePools match the workloadsNodePool overrides.

Usage: verify-workload-nodepool.py --kube-context C --overrides overrides.json --chart-values values.yaml
"""
import argparse
import json
import subprocess
import sys

LIST_FIELDS = {
    "architectures": "kubernetes.io/arch",
    "capacityTypes": "karpenter.sh/capacity-type",
    "instanceCategories": "eks.amazonaws.com/instance-category",
    "instanceSizes": "eks.amazonaws.com/instance-size",
}
GENERATION_LABEL = "eks.amazonaws.com/instance-generation"


def kubectl_json(context, *args):
    out = subprocess.run(["kubectl", f"--context={context}", *args, "-o", "json"],
                         check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def chart_pools(values_file):
    # yq (mikefarah) is on the GitHub-hosted runners; it avoids a PyYAML dependency.
    out = subprocess.run(["yq", "-o", "json", ".workloadsNodePool", values_file],
                         check=True, capture_output=True, text=True).stdout
    return json.loads(out) or {}


def node_label_failures(name, field, nodes, label, accepts, expectation):
    """One failure per node whose label value `accepts` rejects."""
    failures = []
    for node in nodes:
        value = node["metadata"]["labels"].get(label)
        if not accepts(value):
            failures.append(f"{name} {field}: node {node['metadata']['name']} has {label}={value}, {expectation}")
    return failures


def check_list_field(name, field, label, requirement, nodes, expected):
    """A list override (architectures, capacityTypes, ...): an `In` requirement with exactly those values, and every
    node labelled with one of them. No values means no requirement at all."""
    want = {str(v) for v in expected.get(field) or []}
    if not want:
        if requirement:
            return [f"{name} {field}: expected no {label} requirement, found {requirement['values']}"]
        return []
    failures = []
    got = {str(v) for v in requirement["values"]} if requirement else None
    if not requirement or requirement.get("operator") != "In" or got != want:
        found = f"{requirement.get('operator')} {sorted(got)}" if requirement else "no requirement"
        failures.append(f"{name} {field}: expected {label} In {sorted(want)}, found {found}")
    return failures + node_label_failures(name, field, nodes, label, lambda value: value in want,
                                          f"expected one of {sorted(want)}")


def check_min_generation(name, requirement, nodes, expected):
    """minInstanceGeneration: a `Gte [minimum]` requirement, and every node at that generation or newer."""
    field = "minInstanceGeneration"
    minimum = expected.get(field)
    if not minimum:
        if requirement:
            return [f"{name} {field}: expected no {GENERATION_LABEL} requirement, "
                    f"found {requirement.get('operator')} {requirement['values']}"]
        return []
    failures = []
    got = [str(v) for v in requirement["values"]] if requirement else None
    if not requirement or requirement.get("operator") != "Gte" or got != [str(minimum)]:
        found = f"{requirement.get('operator')} {got}" if requirement else "no requirement"
        failures.append(f"{name} {field}: expected {GENERATION_LABEL} Gte [{minimum}], found {found}")
    return failures + node_label_failures(
        name, field, nodes, GENERATION_LABEL,
        lambda value: (value or "").isdigit() and int(value) >= int(minimum), f"expected >= {minimum}")


def check_spec_section(name, section, actual, expected):
    """A map override (disruption, limits): each expected key has the expected value on the NodePool."""
    return [f"{name} {section}.{key}: expected {want}, found {actual.get(key)}"
            for key, want in expected[section].items() if str(actual.get(key)) != str(want)]


def check_pool(name, node_pool, nodes, expected, inherited, shared):
    """Every override the pool inherits, on the NodePool and on the nodes it launched. Limits apply only to the
    shared pool."""
    requirements = {r["key"]: r for r in node_pool["spec"]["template"]["spec"].get("requirements", [])}
    failures = []
    for field, label in LIST_FIELDS.items():
        if inherited(field):
            failures += check_list_field(name, field, label, requirements.get(label), nodes, expected)
    if inherited("minInstanceGeneration"):
        failures += check_min_generation(name, requirements.get(GENERATION_LABEL), nodes, expected)
    sections = {"disruption": node_pool["spec"].get("disruption") or {}}
    if shared:
        sections["limits"] = node_pool["spec"].get("limits") or {}
    for section, actual in sections.items():
        if inherited(section):
            failures += check_spec_section(name, section, actual, expected)
    return failures


def workload_pools(expected, chart):
    """The pools to check: the shared pool, plus the dedicated pools in isolated mode, with the chart defaults that
    replace settings they would otherwise inherit."""
    mode = str(expected.get("mode") or chart.get("mode") or "isolated")
    pools = [{"name": str(expected.get("name") or chart.get("name") or "general-work-pool"),
              "overrides": {}, "shared": True}]
    if mode == "isolated":
        for key in ("kafka", "proxy", "replayer"):
            pool = chart["dedicatedPools"][key]
            pools.append({"name": str(pool["name"]), "overrides": pool, "shared": False})
    return mode, pools


def print_nodes(pool_name, nodes):
    print(f"{pool_name} nodes:")
    for n in nodes:
        labels = n["metadata"]["labels"]
        print(f"  {n['metadata']['name']}: {labels.get('node.kubernetes.io/instance-type')} "
              f"arch={labels.get('kubernetes.io/arch')} capacity={labels.get('karpenter.sh/capacity-type')}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--kube-context", required=True)
    parser.add_argument("--overrides", required=True, help="JSON file with the workloadsNodePool overrides")
    parser.add_argument("--chart-values", required=True)
    args = parser.parse_args()

    with open(args.overrides) as f:
        expected = json.load(f)["workloadsNodePool"]
    mode, pools = workload_pools(expected, chart_pools(args.chart_values))

    failures, total_nodes = [], 0
    for pool in pools:
        def inherited(field, pool=pool):
            return field in expected and field not in pool["overrides"]
        node_pool = kubectl_json(args.kube_context, "get", "nodepool", pool["name"])
        nodes = kubectl_json(args.kube_context, "get", "nodes", "-l", f"karpenter.sh/nodepool={pool['name']}")["items"]
        total_nodes += len(nodes)
        failures += check_pool(pool["name"], node_pool, nodes, expected, inherited, pool["shared"])
        print_nodes(pool["name"], nodes)
    if total_nodes == 0:
        failures.append(f"no workload NodePool ({', '.join(p['name'] for p in pools)}) launched nodes, "
                        "so the overrides were never exercised")
    if failures:
        print("Workload NodePools do not match the workloadsNodePool overrides:\n  - " + "\n  - ".join(failures),
              file=sys.stderr)
        sys.exit(1)
    print(f"Workload NodePools ({mode}) match the workloadsNodePool overrides: {expected}")


if __name__ == "__main__":
    main()
