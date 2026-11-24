#!/usr/bin/env python3
"""RFS memory-tier fleet scheduler: one monitor sweep.

The monitor publishes STATE, not orders: each sweep recomputes the per-tier pod allocation from
coordinator truth and publishes it as per-tier lease caps (the maxLeases field on the
shard_setup sentinel work item). Workers are read-only consumers that
idle only when their OWN tier's live-lease count meets its cap, so herding is impossible by
construction, and a dead monitor leaves at worst a tier running slightly under capacity.

Allocation precedence (highest wins when the budget cannot satisfy all three):
  1. busy floor      — never set a tier's allocation below its lease-holding pods (inviolable)
  2. higher-tier demand, tier-descending — each tier gets min(eligible, remaining budget)
  3. discovery floor — one pod for the lowest tier with never-attempted items, funded only from
                       budget left after (2); greedy wins over discovery when they conflict

Usage:
  rfsTierSweep.py --compute-only   # pure: sweep-input JSON on stdin -> allocation JSON on stdout
  rfsTierSweep.py                  # gather (kubectl + coordinator) -> compute -> apply

Env (apply mode): TIER_POLICY_CONFIGMAP, SNAPSHOT_MIGRATION_NAME, PARENT_WORKFLOW_NAME,
MIGRATION_SERVICES_CONFIG (default /config/migration_services.yaml), POD_NAMESPACE optional.
"""

import argparse
import json
import logging
import os
import sys
import time

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("rfs-tier-sweep")

HIGH_DELETION_COST = "10000"
LOW_DELETION_COST = "0"
POD_DELETION_COST_ANNOTATION = "controller.kubernetes.io/pod-deletion-cost"
# The per-tier caps live as a field ON the shard_setup sentinel work item: every status/work
# query already excludes that document, so the caps need no index of their own.
SHARD_SETUP_DOC_ID = "shard_setup"
WORKING_STATE_BASENAME = ".migrations_working_state"
CRD_GROUP = "migrations.opensearch.org"
CRD_VERSION = "v1alpha1"


# ──────────────────────────────────────────────────────────────────────────────
# Pure allocation arithmetic (exercised by fixture-driven tests via --compute-only)
# ──────────────────────────────────────────────────────────────────────────────

def pod_name_of_worker_id(worker_id):
    """workerId is <podName>_<uuid-per-process>; the pod name is everything before the last '_'."""
    return worker_id.rsplit("_", 1)[0]


def compute_allocation(sweep_input):
    """Pure per-sweep arithmetic.

    sweep_input:
      maxTotalPods: int
      escalationApproval: "auto" | "manual"
      tiers: [ {tier, eligible, waiting, neverAttempted, busyPods, deploymentExists,
                currentReplicas, approval: "approved"|"pending"|"none"} ]
        - eligible: incomplete items at this tier (leased + waiting)
        - waiting: the unclaimed subset (expiration < now)
        - busyPods: number of DISTINCT pods currently holding live leases at this tier
    returns:
      caps: {tier: maxLeases}           # Σ caps ≤ maxTotalPods (above the inviolable busy floor)
      replicas: {tier: n}               # desired replicas for existing/created deployments
      create: [tier]                    # tiers to (re)create from the rendered ConfigMap
      requestApproval: [tier]           # manual-mode tiers whose first activation needs a gate
    """
    budget = int(sweep_input["maxTotalPods"])
    manual = sweep_input.get("escalationApproval") == "manual"
    tiers = sorted(sweep_input["tiers"], key=lambda t: int(t["tier"]))

    alloc = {}
    blocked = {}
    request_approval = []
    for state in tiers:
        tier = int(state["tier"])
        # Busy floor: inviolable, even if it exceeds the budget (never delete a busy pod).
        alloc[tier] = int(state.get("busyPods", 0))
        activated = bool(state.get("deploymentExists")) or int(state.get("busyPods", 0)) > 0
        blocked[tier] = (manual and tier > 0 and not activated
                         and state.get("approval") != "approved")
        if blocked[tier] and int(state.get("waiting", 0)) > 0 \
                and state.get("approval") != "pending":
            request_approval.append(tier)

    remaining = max(0, budget - sum(alloc.values()))

    # Higher-tier demand first: escalated shards are revealed-long chains whose remaining
    # length is irreducible wall-clock, so budget funds them before lower-tier breadth.
    for state in sorted(tiers, key=lambda t: -int(t["tier"])):
        tier = int(state["tier"])
        if blocked[tier]:
            continue  # unapproved tier's cap stays at its (zero) busy floor
        eligible = int(state.get("eligible", 0))
        want = min(eligible, alloc[tier] + remaining)
        extra = max(0, want - alloc[tier])
        alloc[tier] += extra
        remaining -= extra

    # Discovery floor: keep one pod on the lowest tier that still has never-attempted items,
    # but only from budget left over (greedy wins over discovery).
    for state in tiers:
        tier = int(state["tier"])
        if int(state.get("neverAttempted", 0)) > 0 and not blocked[tier]:
            if alloc[tier] == 0 and remaining > 0:
                alloc[tier] = 1
                remaining -= 1
            break

    create = []
    replicas = {}
    for state in tiers:
        tier = int(state["tier"])
        replicas[tier] = alloc[tier]
        if alloc[tier] > 0 and not state.get("deploymentExists"):
            create.append(tier)

    return {
        "caps": {str(t): alloc[t] for t in alloc},
        "replicas": {str(t): replicas[t] for t in replicas},
        "create": create,
        "requestApproval": request_approval,
    }


def compute_pod_deletion_costs(pods_by_tier, busy_pod_names, replicas):
    """Annotation plan for over-allocated tiers: busy pods high, idle pods low — set immediately
    before each scale patch so the ReplicaSet's victim ranking prefers the idle ones."""
    costs = {}
    for tier, pods in pods_by_tier.items():
        if int(replicas.get(str(tier), 0)) >= len(pods):
            continue  # not shrinking this tier; leave annotations alone
        for pod in pods:
            costs[pod] = HIGH_DELETION_COST if pod in busy_pod_names else LOW_DELETION_COST
    return costs


# ──────────────────────────────────────────────────────────────────────────────
# Gather + apply (cluster-touching; not exercised by the fixture tests)
# ──────────────────────────────────────────────────────────────────────────────

def load_policy(core, namespace, configmap_name):
    cm = core.read_namespaced_config_map(name=configmap_name, namespace=namespace)
    policy = json.loads(cm.data["policy.json"])
    manifests = {}
    for key, value in cm.data.items():
        if key.startswith("deployment-t") or key.startswith("pdb-t"):
            manifests[key] = json.loads(value)
    return policy, manifests


def working_state_index(session_name):
    return WORKING_STATE_BASENAME + (("_" + session_name) if session_name else "")


def coordinator_search(cluster, index, body):
    from console_link.models.cluster import HttpMethod
    response = cluster.call_api(f"/{index}/_search", method=HttpMethod.POST,
                                data=json.dumps(body), headers={"Content-Type": "application/json"})
    return response.json()


def gather_coordinator_state(cluster, index, tier_count):
    now = int(time.time())
    body = {
        "size": 0,
        "query": {"bool": {
            "must": [{"exists": {"field": "expiration"}}],
            "must_not": [{"exists": {"field": "completedAt"}}],
        }},
        "aggs": {"byTier": {
            "terms": {"field": "requiredMemoryTier", "missing": 0, "value_type": "long", "size": 100},
            "aggs": {
                "waiting": {"filter": {"range": {"expiration": {"lt": now}}}},
                "neverAttempted": {"filter": {"bool": {
                    "must": [{"term": {"expiration": 0}}],
                    "must_not": [{"exists": {"field": "leaseAcquiredAt"}}],
                }}},
            },
        }},
    }
    agg = coordinator_search(cluster, index, body)
    tiers = {t: {"tier": t, "eligible": 0, "waiting": 0, "neverAttempted": 0, "busyPods": 0}
             for t in range(tier_count)}
    total_incomplete = agg.get("hits", {}).get("total", {})
    if isinstance(total_incomplete, dict):
        total_incomplete = total_incomplete.get("value", 0)
    for bucket in agg.get("aggregations", {}).get("byTier", {}).get("buckets", []):
        tier = int(bucket["key"])
        entry = tiers.setdefault(tier, {"tier": tier, "eligible": 0, "waiting": 0,
                                        "neverAttempted": 0, "busyPods": 0})
        entry["eligible"] = bucket["doc_count"]
        entry["waiting"] = bucket["waiting"]["doc_count"]
        entry["neverAttempted"] = bucket["neverAttempted"]["doc_count"]

    # Busy pods per tier, read from lease truth (never from k8s): pods named in live leases.
    leases_body = {
        "size": 1000,
        "_source": ["leaseHolderId", "requiredMemoryTier"],
        "query": {"bool": {
            "must": [{"range": {"expiration": {"gte": now}}}],
            "must_not": [{"exists": {"field": "completedAt"}}],
        }},
    }
    leases = coordinator_search(cluster, index, leases_body)
    busy_pods_by_tier = {}
    for hit in leases.get("hits", {}).get("hits", []):
        source = hit.get("_source", {})
        holder = source.get("leaseHolderId")
        if not holder:
            continue
        tier = int(source.get("requiredMemoryTier", 0) or 0)
        busy_pods_by_tier.setdefault(tier, set()).add(pod_name_of_worker_id(holder))
    for tier, pods in busy_pods_by_tier.items():
        entry = tiers.setdefault(tier, {"tier": tier, "eligible": 0, "waiting": 0,
                                        "neverAttempted": 0, "busyPods": 0})
        entry["busyPods"] = len(pods)

    busy_pod_names = set()
    for pods in busy_pods_by_tier.values():
        busy_pod_names.update(pods)
    return sorted(tiers.values(), key=lambda t: t["tier"]), int(total_incomplete), busy_pod_names


def approval_gate_name(sm_name, tier):
    return f"memorytier.{sm_name}.t{tier}"


def read_approval(custom, namespace, sm_name, tier):
    from kubernetes.client.rest import ApiException
    try:
        gate = custom.get_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION, namespace=namespace,
            plural="approvalgates", name=approval_gate_name(sm_name, tier))
        phase = (gate.get("status") or {}).get("phase", "")
        return "approved" if phase == "Approved" else "pending"
    except ApiException as e:
        if e.status == 404:
            return "none"
        raise


def create_approval_gate(custom, namespace, sm_name, parent_workflow_name, tier):
    from kubernetes.client.rest import ApiException
    body = {
        "apiVersion": f"{CRD_GROUP}/{CRD_VERSION}",
        "kind": "ApprovalGate",
        "metadata": {
            "name": approval_gate_name(sm_name, tier),
            "labels": {
                f"{CRD_GROUP}/workflow": parent_workflow_name,
                f"{CRD_GROUP}/approval-category": "change",
            },
        },
        "spec": {},
    }
    try:
        custom.create_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION, namespace=namespace,
            plural="approvalgates", body=body)
        logger.info("Requested approval for memory tier %s via gate %s", tier,
                    approval_gate_name(sm_name, tier))
    except ApiException as e:
        if e.status != 409:
            raise


def publish_lease_caps(cluster, index, caps):
    """Partial-update maxLeases on the shard_setup sentinel. Update-if-exists only: upserting
    could fabricate a sentinel without the fields the coordination scripts expect. The sentinel
    is created at setup, so a 404 just means this sweep ran first — the next one retries.

    OS takes the typeless _update path; ES 6.8 has no typeless _update endpoint (only _doc
    get/index are typeless there), so a 400 invalid_type_name falls back to the typed path the
    java client writes with."""
    import requests
    from console_link.models.cluster import HttpMethod
    body = json.dumps({"doc": {"maxLeases": caps}})
    paths = (f"/{index}/_update/{SHARD_SETUP_DOC_ID}?refresh=true",
             f"/{index}/doc/{SHARD_SETUP_DOC_ID}/_update?refresh=true")
    for attempt, path in enumerate(paths):
        try:
            cluster.call_api(path, method=HttpMethod.POST, data=body,
                             headers={"Content-Type": "application/json"})
            return
        except requests.HTTPError as e:
            status = e.response.status_code if e.response is not None else None
            if status == 400 and attempt == 0:
                continue  # ES 6.8: retry on the typed path
            if status == 404:
                logger.info("shard_setup sentinel not created yet; caps publish deferred to next sweep")
                return
            raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compute-only", action="store_true",
                        help="read sweep-input JSON on stdin, print the allocation JSON")
    args = parser.parse_args()

    if args.compute_only:
        sweep_input = json.load(sys.stdin)
        result = compute_allocation(sweep_input)
        if "podsByTier" in sweep_input:
            result["podDeletionCosts"] = compute_pod_deletion_costs(
                {int(k): v for k, v in sweep_input["podsByTier"].items()},
                set(sweep_input.get("busyPodNames", [])),
                result["replicas"])
        json.dump(result, sys.stdout, indent=2, sort_keys=True)
        print()
        return 0

    # ── apply mode ────────────────────────────────────────────────────────────
    from kubernetes import client, config
    from console_link.environment import Environment

    config.load_incluster_config()
    core = client.CoreV1Api()
    apps = client.AppsV1Api()
    policy_api = client.PolicyV1Api()
    custom = client.CustomObjectsApi()
    namespace = os.environ.get("POD_NAMESPACE") or open(
        "/var/run/secrets/kubernetes.io/serviceaccount/namespace").read().strip()

    configmap_name = os.environ["TIER_POLICY_CONFIGMAP"]
    sm_name = os.environ["SNAPSHOT_MIGRATION_NAME"]
    parent_workflow_name = os.environ.get("PARENT_WORKFLOW_NAME", "")
    services_config = os.environ.get("MIGRATION_SERVICES_CONFIG", "/config/migration_services.yaml")

    try:
        policy, manifests = load_policy(core, namespace, configmap_name)
    except Exception as e:  # noqa: BLE001
        logger.info("Tier policy ConfigMap %s not readable yet (%s); skipping tier sweep",
                    configmap_name, e)
        return 0

    env = Environment(config_file=services_config)
    cluster = env.target_cluster
    index = working_state_index(policy.get("sessionName", ""))
    tier_table = policy["tierTable"]
    tier_label = policy["tierLabel"]
    manual = policy.get("escalationApproval") == "manual"

    try:
        tiers, total_incomplete, busy_pod_names = gather_coordinator_state(
            cluster, index, len(tier_table))
    except Exception as e:  # noqa: BLE001
        logger.info("Coordinator state unavailable (%s); skipping tier sweep", e)
        return 0

    # Overlay k8s state: which tier deployments exist, their replicas, and their pods.
    sm = custom.get_namespaced_custom_object(
        group=CRD_GROUP, version=CRD_VERSION, namespace=namespace,
        plural="snapshotmigrations", name=sm_name)
    sm_uid = sm["metadata"]["uid"]
    selector = (f"{CRD_GROUP}/component=rfs-worker,"
                f"{CRD_GROUP}/migration-resource-uid={sm_uid}")
    deployments = apps.list_namespaced_deployment(namespace, label_selector=selector).items
    deployment_by_tier = {}
    for deployment in deployments:
        tier_value = (deployment.metadata.labels or {}).get(tier_label)
        if tier_value is not None:
            deployment_by_tier[int(tier_value)] = deployment
    pods = core.list_namespaced_pod(namespace, label_selector=selector).items
    pods_by_tier = {}
    for pod in pods:
        tier_value = (pod.metadata.labels or {}).get(tier_label)
        if tier_value is not None and pod.metadata.deletion_timestamp is None:
            pods_by_tier.setdefault(int(tier_value), []).append(pod.metadata.name)

    for state in tiers:
        tier = state["tier"]
        state["deploymentExists"] = tier in deployment_by_tier
        state["currentReplicas"] = (deployment_by_tier[tier].spec.replicas or 0) \
            if tier in deployment_by_tier else 0
        state["approval"] = read_approval(custom, namespace, sm_name, tier) if manual and tier > 0 \
            else "approved"

    sweep_input = {
        "maxTotalPods": policy["maxTotalPods"],
        "escalationApproval": policy.get("escalationApproval", "auto"),
        "tiers": tiers,
    }
    result = compute_allocation(sweep_input)
    logger.info("Sweep input: %s", json.dumps(sweep_input, sort_keys=True))
    logger.info("Allocation: %s", json.dumps(result, sort_keys=True))

    # Approval requests come first so a pending gate never blocks publishing lower-tier caps.
    for tier in result["requestApproval"]:
        create_approval_gate(custom, namespace, sm_name, parent_workflow_name, tier)

    # Publish the caps: the declarative allocation workers consume at every acquire boundary.
    publish_lease_caps(cluster, index, result["caps"])

    # Trim: annotate costs from lease truth immediately before each scale patch, then patch.
    costs = compute_pod_deletion_costs(pods_by_tier, busy_pod_names, result["replicas"])
    for pod_name, cost in costs.items():
        core.patch_namespaced_pod(pod_name, namespace, {
            "metadata": {"annotations": {POD_DELETION_COST_ANNOTATION: cost}}})

    for state in tiers:
        tier = state["tier"]
        desired = result["replicas"].get(str(tier), 0)
        if tier in deployment_by_tier:
            current = deployment_by_tier[tier].spec.replicas or 0
            if current != desired:
                apps.patch_namespaced_deployment_scale(
                    deployment_by_tier[tier].metadata.name, namespace,
                    {"spec": {"replicas": desired}})
                logger.info("Scaled tier %s: %s -> %s", tier, current, desired)

    # Reactivate: eligible work at a tier with no fleet -> create it from the rendered manifests.
    from kubernetes.client.rest import ApiException
    for tier in result["create"]:
        dep_manifest = manifests.get(f"deployment-t{tier}.json")
        pdb_manifest = manifests.get(f"pdb-t{tier}.json")
        if dep_manifest is None:
            logger.warning("No rendered manifest for tier %s; cannot create its fleet", tier)
            continue
        dep_manifest = json.loads(json.dumps(dep_manifest))
        dep_manifest["spec"]["replicas"] = result["replicas"][str(tier)]
        try:
            apps.create_namespaced_deployment(namespace, dep_manifest)
            logger.info("Created tier %s deployment with %s replica(s)", tier,
                        dep_manifest["spec"]["replicas"])
        except ApiException as e:
            if e.status != 409:
                raise
            apps.patch_namespaced_deployment_scale(
                dep_manifest["metadata"]["name"], namespace,
                {"spec": {"replicas": result["replicas"][str(tier)]}})
        if pdb_manifest is not None:
            try:
                policy_api.create_namespaced_pod_disruption_budget(namespace, pdb_manifest)
            except ApiException as e:
                if e.status != 409:
                    raise
    return 0


if __name__ == "__main__":
    sys.exit(main())
