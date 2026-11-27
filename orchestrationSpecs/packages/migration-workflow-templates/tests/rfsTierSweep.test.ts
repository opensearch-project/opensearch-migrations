import {execFileSync} from "child_process";
import * as path from "path";

// Fixture-driven tests for the monitor's per-sweep arithmetic (rfsTierSweep.py --compute-only):
// the allocation precedence is busy-floor > higher-tier demand > discovery floor, caps are
// demand-bounded, and pod-deletion costs only annotate tiers that are actually shrinking.

const SCRIPT = path.resolve(__dirname, "../resources/scripts/rfsTierSweep.py");

function compute(input: unknown): any {
    const out = execFileSync("python3", [SCRIPT, "--compute-only"], {
        input: JSON.stringify(input),
        encoding: "utf-8",
    });
    return JSON.parse(out);
}

function tier(overrides: Record<string, unknown>) {
    return {
        eligible: 0, waiting: 0, neverAttempted: 0, busyPods: 0, ghostLeases: 0,
        deploymentExists: false, currentReplicas: 0,
        ...overrides,
    };
}

describe("rfsTierSweep allocation arithmetic", () => {
    it("gives every tier its demand when the budget covers it", () => {
        const result = compute({
            maxTotalPods: 24,
            tiers: [
                tier({tier: 0, eligible: 10, waiting: 6, busyPods: 4, deploymentExists: true}),
                tier({tier: 1, eligible: 2, waiting: 2}),
            ],
        });
        expect(result.caps).toEqual({"0": 10, "1": 2});
        expect(result.replicas).toEqual({"0": 10, "1": 2});
        expect(result.create).toEqual([1]);
    });

    it("never allocates a tier more pods than it has work for", () => {
        const result = compute({
            maxTotalPods: 100,
            tiers: [tier({tier: 0, eligible: 3, waiting: 3, deploymentExists: true})],
        });
        expect(result.caps).toEqual({"0": 3});
    });

    it("funds higher-tier demand first when the budget is short (greedy escalation)", () => {
        const result = compute({
            maxTotalPods: 5,
            tiers: [
                tier({tier: 0, eligible: 20, waiting: 20, deploymentExists: true}),
                tier({tier: 2, eligible: 3, waiting: 3}),
            ],
        });
        // Tier 2 takes its full demand (3); tier 0 gets what's left (2).
        expect(result.caps).toEqual({"0": 2, "2": 3});
    });

    it("treats the busy floor as inviolable, even above the budget", () => {
        const result = compute({
            maxTotalPods: 4,
            tiers: [
                tier({tier: 0, eligible: 10, waiting: 2, busyPods: 6, deploymentExists: true}),
                tier({tier: 1, eligible: 2, waiting: 2}),
            ],
        });
        // Six pods hold leases at tier 0: never trimmed below busy; no budget remains for tier 1.
        expect(result.caps["0"]).toBe(6);
        expect(result.caps["1"]).toBe(0);
        expect(result.create).toEqual([]);
    });

    it("drains lower tiers only down to their busy floor as higher tiers demand budget", () => {
        const result = compute({
            maxTotalPods: 10,
            tiers: [
                tier({tier: 0, eligible: 20, waiting: 12, busyPods: 8, deploymentExists: true}),
                tier({tier: 1, eligible: 4, waiting: 4}),
            ],
        });
        // Remaining budget after the 8 busy tier-0 pods is 2; tier 1 takes both.
        expect(result.caps).toEqual({"0": 8, "1": 2});
    });

    it("keeps one discovery pod on the lowest tier with never-attempted items when budget allows", () => {
        const result = compute({
            maxTotalPods: 10,
            tiers: [
                tier({tier: 0, eligible: 3, waiting: 3, neverAttempted: 3, deploymentExists: true}),
                tier({tier: 1, eligible: 2, waiting: 2}),
            ],
        });
        expect(result.caps).toEqual({"0": 3, "1": 2});

        const squeezed = compute({
            maxTotalPods: 2,
            tiers: [
                tier({tier: 0, eligible: 3, waiting: 3, neverAttempted: 3, deploymentExists: true}),
                tier({tier: 1, eligible: 2, waiting: 2}),
            ],
        });
        // Greedy wins over discovery: the two budget slots go to tier 1, discovery gets nothing.
        expect(squeezed.caps).toEqual({"0": 0, "1": 2});

        const roomy = compute({
            maxTotalPods: 3,
            tiers: [
                tier({tier: 0, eligible: 3, waiting: 3, neverAttempted: 3, deploymentExists: true}),
                tier({tier: 1, eligible: 2, waiting: 2}),
            ],
        });
        // One slot left after tier 1 — discovery... actually tier 0's own demand claims it in
        // step 2, which is the same outcome the discovery floor protects.
        expect(roomy.caps).toEqual({"0": 1, "1": 2});
    });

    it("reactivates a wound-down tier whose work reappears (e.g. an abandoned lease expiring late)", () => {
        const result = compute({
            maxTotalPods: 10,
            tiers: [
                tier({tier: 0, eligible: 0, deploymentExists: false}),
                tier({tier: 1, eligible: 1, waiting: 1, deploymentExists: false}),
            ],
        });
        expect(result.create).toEqual([1]);
        expect(result.caps).toEqual({"0": 0, "1": 1});
    });

    it("does not pin a pod for a lease whose holder pod is gone, but keeps its lease slot in the cap", () => {
        // Replays run h's 22:24:01 sweep: tier 0's only incomplete item was leased by a pod the
        // ReplicaSet had already deleted. Counting that holder as busy kept a replica alive and
        // used up the cap, so the surviving tier-0 pod idled at "cap reached".
        const result = compute({
            maxTotalPods: 6,
            tiers: [
                tier({tier: 0, eligible: 1, waiting: 0, busyPods: 0, ghostLeases: 1,
                      deploymentExists: true, currentReplicas: 2}),
                tier({tier: 1, eligible: 0, deploymentExists: true, currentReplicas: 3}),
                tier({tier: 2, eligible: 1, waiting: 1}),
            ],
        });
        expect(result.replicas).toEqual({"0": 0, "1": 0, "2": 1});
        expect(result.caps).toEqual({"0": 1, "1": 0, "2": 1});
    });

    it("funds a tier's waiting work around a ghost lease without counting the ghost as demand", () => {
        const result = compute({
            maxTotalPods: 10,
            tiers: [tier({tier: 0, eligible: 3, waiting: 2, busyPods: 0, ghostLeases: 1,
                          deploymentExists: true, currentReplicas: 3})],
        });
        // Two pods for the two waiting items; the cap leaves room for the ghost's lease too, so
        // both pods can acquire while the ghost lease is still live.
        expect(result.replicas).toEqual({"0": 2});
        expect(result.caps).toEqual({"0": 3});
    });

    it("writes only the deletion-cost annotations that change", () => {
        const result = compute({
            maxTotalPods: 4,
            tiers: [tier({tier: 0, eligible: 2, waiting: 0, busyPods: 2, deploymentExists: true,
                          currentReplicas: 4})],
            podsByTier: {"0": ["pod-a", "pod-b", "pod-c", "pod-d"]},
            busyPodNames: ["pod-a", "pod-b"],
            currentDeletionCosts: {"pod-a": "10000", "pod-b": "0", "pod-c": "0"},
        });
        // pod-a and pod-c already hold their target costs; pod-b just became busy and pod-d has none.
        expect(result.podDeletionCosts).toEqual({"pod-b": "10000", "pod-d": "0"});
    });

    it("annotates deletion costs only on shrinking tiers: busy pods high, idle pods low", () => {
        const result = compute({
            maxTotalPods: 4,
            tiers: [
                tier({tier: 0, eligible: 3, waiting: 1, busyPods: 2, deploymentExists: true, currentReplicas: 5}),
                tier({tier: 1, eligible: 1, waiting: 1, deploymentExists: true, currentReplicas: 1}),
            ],
            podsByTier: {
                "0": ["pod-a", "pod-b", "pod-c", "pod-d", "pod-e"],
                "1": ["pod-z"],
            },
            busyPodNames: ["pod-a", "pod-b"],
        });
        // Tier 0 shrinks from 5 pods to 3: cost every tier-0 pod, high for the lease holders.
        expect(result.podDeletionCosts).toEqual({
            "pod-a": "10000", "pod-b": "10000",
            "pod-c": "0", "pod-d": "0", "pod-e": "0",
        });
    });
});

describe("kernel-OOM witness (compute_oom_evidence)", () => {
    // A cgroup OOM group-kills the whole container, supervisor included, so the evidence must come
    // from outside: the monitor matches the kubelet's OOMKilled window against the dirty lease's
    // holder instance id (which begins with the dead container's start epoch) and annotates the pod.
    const base = {
        maxTotalPods: 4,
        tiers: [tier({tier: 0, eligible: 1, busyPods: 0, ghostLeases: 1, deploymentExists: true})],
    };

    it("attributes the dirty lease whose instance epoch falls inside the dead container's lifetime", () => {
        const result = compute({
            ...base,
            oomRestarts: {"bulk-loader-t0-abc": [1795582000, 1795582100]},
            leaseHolderIds: [
                "bulk-loader-t0-abc_1795582005-2",   // launched by the dead container
                "bulk-loader-t0-abc_1795583000-1",   // the restarted container's own worker
                "other-pod_1795582005-1",            // same epoch, different pod
            ],
        });
        expect(result.oomEvidence).toEqual({"bulk-loader-t0-abc": "1795582005-2=OOMKilled"});
    });

    it("keeps existing evidence entries, skips already-known instances, and emits only changes", () => {
        const result = compute({
            ...base,
            oomRestarts: {
                "pod-x": [1795582000, 1795582100],
                "pod-y": [1795582000, 1795582100],
            },
            leaseHolderIds: ["pod-x_1795582010-3", "pod-y_1795582010-1"],
            currentEvidence: {
                "pod-x": "1795570000-1=OOMKilled",         // older entry must survive
                "pod-y": "1795582010-1=OOMKilled",         // already recorded: no patch
            },
        });
        expect(result.oomEvidence).toEqual({
            "pod-x": "1795570000-1=OOMKilled,1795582010-3=OOMKilled",
        });
    });

    it("ignores unsupervised (uuid-style) worker ids and pods with no matching holder", () => {
        const result = compute({
            ...base,
            oomRestarts: {"pod-x": [1795582000, 1795582100]},
            leaseHolderIds: [
                "pod-x_c0ffee00-1111-2222-3333-444455556666",  // uuid id: no epoch to match
                "pod-x_1795590000-1",                           // started after the kill window
            ],
        });
        expect(result.oomEvidence).toEqual({});
    });
});
