/**
 * Authoritative topology test for fullMigrationWithTraffic.wf.yaml.
 *
 * This test runs the real `MigrationInitializer` on the real sample
 * baseline YAML and derives a component topology from its output. It
 * then compares that derived topology to the hardcoded one in
 * `componentTopologyResolver`. If the generator or the sample change
 * in a way that drifts from the hardcoded topology, this test fails
 * with a readable diff — much stronger than hand-copying names from
 * another package's unit test.
 */

import * as fs from "node:fs";
import * as path from "node:path";

import { parse as parseYaml } from "yaml";
import { MigrationInitializer } from "@opensearch-migrations/config-processor";

import { requireTopologyForBaseline } from "../src/componentTopologyResolver";
import { ComponentEdge } from "../src/componentTopology";
import { ComponentId } from "../src/types";

const SAMPLE_PATH = path.resolve(
    __dirname,
    "..",
    "..",
    "config-processor",
    "scripts",
    "samples",
    "fullMigrationWithTraffic.wf.yaml",
);

/**
 * CRD `kind` (as emitted by the initializer) mapped to the component-ID
 * prefix we use in the topology. Mirrors the mapping in `k8sClient.ts`,
 * but keyed on the camelcase Kind rather than the plural.
 */
const KIND_TO_PREFIX: Readonly<Record<string, string>> = {
    KafkaCluster: "kafkacluster",
    CapturedTraffic: "capturedtraffic",
    CaptureProxy: "captureproxy",
    DataSnapshot: "datasnapshot",
    SnapshotMigration: "snapshotmigration",
    TrafficReplay: "trafficreplay",
};

async function deriveTopologyFromSample(): Promise<{
    components: ReadonlySet<ComponentId>;
    edges: ReadonlySet<string>; // "from→to"
}> {
    const userConfig = parseYaml(fs.readFileSync(SAMPLE_PATH, "utf8"));
    // Schema validation is intentionally *not* performed here.
    // `generateMigrationBundle` accepts the pre-validated user config,
    // and our job in this test is to verify topology drift, not to
    // duplicate the schema-validation coverage that config-processor
    // owns.
    const initializer = new MigrationInitializer();
    const bundle = await initializer.generateMigrationBundle(userConfig);
    const items: Array<{
        kind?: string;
        metadata?: { name?: string };
        spec?: { dependsOn?: string[] };
    }> = bundle.crdResources.items as any;

    // Build the reverse lookup: resource name → kind, so we can turn
    // the string refs in `spec.dependsOn` into typed ComponentIds.
    const nameToKind = new Map<string, string>();
    for (const item of items) {
        if (!item.kind || !item.metadata?.name) continue;
        if (!(item.kind in KIND_TO_PREFIX)) continue;
        nameToKind.set(item.metadata.name, item.kind);
    }

    const components = new Set<ComponentId>();
    const edges = new Set<string>();

    for (const item of items) {
        if (!item.kind || !item.metadata?.name) continue;
        const prefix = KIND_TO_PREFIX[item.kind];
        if (!prefix) continue; // ApprovalGate and friends — not topology nodes.

        const id = `${prefix}:${item.metadata.name}` as ComponentId;
        components.add(id);

        for (const depName of item.spec?.dependsOn ?? []) {
            const depKind = nameToKind.get(depName);
            if (!depKind || !(depKind in KIND_TO_PREFIX)) continue;
            const depId = `${KIND_TO_PREFIX[depKind]}:${depName}` as ComponentId;
            edges.add(`${id}→${depId}`);
        }
    }
    return { components, edges };
}

describe("fullMigrationWithTraffic topology — authoritative generator check", () => {
    it("hardcoded topology components match what MigrationInitializer emits for the sample", async () => {
        const derived = await deriveTopologyFromSample();
        const hardcoded = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");

        const hardcodedSet = new Set<ComponentId>(hardcoded.components);
        const missingFromHardcoded = [...derived.components].filter((c) => !hardcodedSet.has(c));
        const extraInHardcoded = [...hardcodedSet].filter((c) => !derived.components.has(c));

        expect({ missingFromHardcoded, extraInHardcoded }).toEqual({
            missingFromHardcoded: [],
            extraInHardcoded: [],
        });
    });

    it("hardcoded topology edges match the generated spec.dependsOn graph", async () => {
        const derived = await deriveTopologyFromSample();
        const hardcoded = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");

        const hardcodedEdges = new Set(
            (hardcoded.edges as readonly ComponentEdge[]).map((e) => `${e.from}→${e.to}`),
        );
        const missingFromHardcoded = [...derived.edges].filter((e) => !hardcodedEdges.has(e));
        const extraInHardcoded = [...hardcodedEdges].filter((e) => !derived.edges.has(e));

        expect({ missingFromHardcoded, extraInHardcoded }).toEqual({
            missingFromHardcoded: [],
            extraInHardcoded: [],
        });
    });
});
