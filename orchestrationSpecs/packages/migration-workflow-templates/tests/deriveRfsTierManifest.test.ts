import {execFileSync} from "child_process";
import * as path from "path";

// Fixture tests for the jq transform that derives a tier-n Deployment/PDB from the LIVE tier-0
// object: name, the tier label in metadata/selector/pod-template, the tier's memory on the
// bulk-loader (requests == limits), the tier env value, and the scrub of server-set fields.
// No quantity arithmetic happens in the transform — $memory arrives precomputed.

const JQ_PROGRAM = path.resolve(__dirname, "../resources/scripts/deriveRfsTierManifest.jq");

function derive(manifest: unknown, args: Record<string, string>): any {
    const jqArgs = ["-f", JQ_PROGRAM];
    for (const [key, value] of Object.entries(args)) {
        jqArgs.push("--arg", key, value);
    }
    const out = execFileSync("jq", jqArgs, {input: JSON.stringify(manifest), encoding: "utf-8"});
    return JSON.parse(out);
}

const TIER_LABEL = "migrations.opensearch.org/rfs-memory-tier";

const tier0Deployment = {
    apiVersion: "apps/v1",
    kind: "Deployment",
    metadata: {
        name: "sess-rfs-t0",
        uid: "live-uid",
        resourceVersion: "12345",
        generation: 3,
        creationTimestamp: "2026-11-20T00:00:00Z",
        annotations: {
            "deployment.kubernetes.io/revision": "2",
            "kubectl.kubernetes.io/last-applied-configuration": "{}",
            "keep.me/annotation": "yes",
        },
        labels: {
            "migrations.opensearch.org/component": "rfs-worker",
            "migrations.opensearch.org/migration-resource-uid": "sm-uid",
            [TIER_LABEL]: "0",
        },
    },
    spec: {
        replicas: 7,
        selector: {matchLabels: {
            "migrations.opensearch.org/component": "rfs-worker",
            "migrations.opensearch.org/migration-resource-uid": "sm-uid",
            [TIER_LABEL]: "0",
        }},
        template: {
            metadata: {labels: {
                "migrations.opensearch.org/component": "rfs-worker",
                "migrations.opensearch.org/migration-resource-uid": "sm-uid",
                [TIER_LABEL]: "0",
            }},
            spec: {
                terminationGracePeriodSeconds: 390,
                containers: [
                    {
                        name: "bulk-loader",
                        resources: {
                            requests: {cpu: "3300m", memory: "7000Mi"},
                            limits: {cpu: "3300m", memory: "7000Mi"},
                        },
                        env: [
                            {name: "RFS_WORKER_MEMORY_TIER", value: "0"},
                        ],
                    },
                ],
            },
        },
    },
    status: {replicas: 7},
};

const tier0Pdb = {
    apiVersion: "policy/v1",
    kind: "PodDisruptionBudget",
    metadata: {
        name: "sess-rfs-t0",
        uid: "pdb-uid",
        resourceVersion: "99",
        labels: {[TIER_LABEL]: "0"},
    },
    spec: {
        minAvailable: 0,
        selector: {matchLabels: {
            "migrations.opensearch.org/component": "rfs-worker",
            "migrations.opensearch.org/migration-resource-uid": "sm-uid",
            [TIER_LABEL]: "0",
        }},
    },
    status: {},
};

const args = {
    tier: "2",
    memory: "28000Mi",
    tierLabel: TIER_LABEL,
    name: "sess-rfs-t2",
    container: "bulk-loader",
};

describe("deriveRfsTierManifest.jq", () => {
    const derived = derive(tier0Deployment, args);

    it("renames and re-labels the deployment for the tier (metadata, selector, pod template)", () => {
        expect(derived.metadata.name).toBe("sess-rfs-t2");
        expect(derived.metadata.labels[TIER_LABEL]).toBe("2");
        expect(derived.spec.selector.matchLabels[TIER_LABEL]).toBe("2");
        expect(derived.spec.template.metadata.labels[TIER_LABEL]).toBe("2");
        // The component + uid selector labels survive untouched.
        expect(derived.spec.selector.matchLabels["migrations.opensearch.org/component"]).toBe("rfs-worker");
    });

    it("sets the tier's memory on the bulk loader, requests == limits", () => {
        const [bulkLoader] = derived.spec.template.spec.containers;
        expect(bulkLoader.resources.requests.memory).toBe("28000Mi");
        expect(bulkLoader.resources.limits.memory).toBe("28000Mi");
        expect(bulkLoader.resources.requests.cpu).toBe("3300m");  // CPU carries over unchanged
    });

    it("sets the worker's tier env and stores the manifest dormant (replicas 0)", () => {
        const bulkLoader = derived.spec.template.spec.containers[0];
        expect(bulkLoader.env.find((e: any) => e.name === "RFS_WORKER_MEMORY_TIER").value).toBe("2");
        expect(derived.spec.replicas).toBe(0);
    });

    it("scrubs server-set fields but keeps user annotations and the grace period", () => {
        expect(derived.metadata.uid).toBeUndefined();
        expect(derived.metadata.resourceVersion).toBeUndefined();
        expect(derived.metadata.generation).toBeUndefined();
        expect(derived.metadata.creationTimestamp).toBeUndefined();
        expect(derived.status).toBeUndefined();
        expect(derived.metadata.annotations).toEqual({"keep.me/annotation": "yes"});
        expect(derived.spec.template.spec.terminationGracePeriodSeconds).toBe(390);
    });

    it("derives the tier PDB: renamed, tier-labeled selector, minAvailable preserved", () => {
        const pdb = derive(tier0Pdb, args);
        expect(pdb.metadata.name).toBe("sess-rfs-t2");
        expect(pdb.spec.selector.matchLabels[TIER_LABEL]).toBe("2");
        expect(pdb.spec.minAvailable).toBe(0);
        expect(pdb.metadata.uid).toBeUndefined();
        expect(pdb.status).toBeUndefined();
        // PDBs have no pod template or containers; the Deployment-only edits must not apply.
        expect(pdb.spec.template).toBeUndefined();
        expect(pdb.spec.replicas).toBeUndefined();
    });
});
