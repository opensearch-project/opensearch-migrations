import {MigrationConfigTransformer} from "../src/migrationConfigTransformer";
import {buildResolvedMigrationResources, dryRunResourcePolicy} from "../src/resolvedMigrationResources";
import {readFileSync} from "node:fs";
import {join} from "node:path";
import {parse} from "yaml";

function sequenceConfig() {
    return {
        skipApprovals: true,
        sourceClusters: {
            source: {
                endpoint: "http://source:9200",
                version: "ES 7.10",
                snapshotInfo: {
                    repos: {repo: {repoPathUri: "s3://snapshots/sequence", awsRegion: "us-east-1"}},
                    // Deliberately different from sequence order.
                    snapshots: {
                        third: {repoName: "repo", config: {createSnapshotConfig: {}}},
                        first: {repoName: "repo", config: {createSnapshotConfig: {}}},
                        second: {repoName: "repo", config: {createSnapshotConfig: {}}},
                    },
                },
            },
        },
        targetClusters: {target: {endpoint: "http://target:9200"}},
        snapshotMigrationConfigs: [{
            fromSource: "source",
            toTarget: "target",
            snapshotSequence: ["first", "second", "third"],
            perSnapshotConfig: {
                first: [{metadataMigrationConfig: {}, documentBackfillConfig: {skipApproval: false}}],
                second: [{metadataMigrationConfig: {}, documentBackfillConfig: {skipApproval: false}}],
                third: [{metadataMigrationConfig: {}, documentBackfillConfig: {skipApproval: false}}],
            },
        }],
    };
}

describe("successive snapshot backfill plans", () => {
    it("accepts the documented successive snapshot configuration", async () => {
        const config = parse(readFileSync(join(__dirname, "../../../examples/successive-snapshots.yaml"), "utf8"));
        const output = await new MigrationConfigTransformer().processFromObject(config);
        expect(output.snapshotSequences?.[0].steps).toHaveLength(5);
    });

    it("takes each snapshot only after the preceding full backfill, with a global delete/add barrier", async () => {
        const output = await new MigrationConfigTransformer().processFromObject(sequenceConfig());
        const plan = output.snapshotSequences![0];
        const migrations = plan.steps.map(step => output.snapshotMigrations[step.migrationIndex]);
        expect(migrations.map(m => [m.label, m.delta?.mode])).toEqual([
            ["first", undefined],
            ["second", "DELETES_ONLY"],
            ["second", "UPDATES_ONLY"],
            ["third", "DELETES_ONLY"],
            ["third", "UPDATES_ONLY"],
        ]);
        expect(plan.steps.filter(step => step.snapshotCreation).map(step => {
            const reference = step.snapshotCreation!;
            return output.snapshots[reference.sourceIndex].createSnapshotConfig[reference.snapshotIndex].label;
        })).toEqual(["first", "second", "third"]);
        expect(new Set(migrations.map(m => m.workloadIdentityChecksum)).size).toBe(5);
        expect(new Set(migrations.map(m => m.resourceName)).size).toBe(5);
        for (const migration of migrations) {
            expect(migration.sequenceName).toBe(plan.name);
            expect(migration.documentBackfillConfig?.serverGeneratedIds).toBe("NEVER");
            if (migration.delta?.mode === "DELETES_ONLY") {
                expect(migration.metadataMigrationConfig).toBeUndefined();
                expect(migration.documentBackfillConfig?.skipApproval).toBe(true);
            } else {
                expect(migration.documentBackfillConfig?.skipApproval).toBe(false);
            }
        }
        expect(migrations[1].delta?.previousSnapshotNameResolution)
            .toEqual({dataSnapshotResourceName: "source-first"});
        expect(migrations[3].delta?.previousSnapshotNameResolution)
            .toEqual({dataSnapshotResourceName: "source-second"});
    });

    it("supports external snapshots, including applying an earlier snapshot after a later one", async () => {
        const config = sequenceConfig();
        const snapshots = Object.fromEntries(
            Object.entries(config.sourceClusters.source.snapshotInfo.snapshots).map(([name, snapshot]) => [
                name, {...snapshot, config: {externallyManagedSnapshotName: `${name}-external`}},
            ]),
        );
        const output = await new MigrationConfigTransformer().processFromObject({
            ...config,
            sourceClusters: {source: {
                ...config.sourceClusters.source,
                snapshotInfo: {...config.sourceClusters.source.snapshotInfo, snapshots},
            }},
            snapshotMigrationConfigs: [{
                ...config.snapshotMigrationConfigs[0],
                snapshotSequence: ["third", "second", "first"],
            }],
        });
        expect(output.snapshots).toEqual([]);
        expect(output.snapshotSequences![0].steps.every(step => !step.snapshotCreation)).toBe(true);
        const second = output.snapshotMigrations.find(m => m.label === "second");
        expect(second?.delta?.previousSnapshotNameResolution).toEqual({externalSnapshotName: "third-external"});
    });

    it("preserves durable migration identities when a successor is appended", async () => {
        const config = sequenceConfig();
        const all = await new MigrationConfigTransformer().processFromObject(config);
        const {third: _third, ...perSnapshotConfig} = config.snapshotMigrationConfigs[0].perSnapshotConfig;
        const prefix = await new MigrationConfigTransformer().processFromObject({
            ...config,
            snapshotMigrationConfigs: [{
                ...config.snapshotMigrationConfigs[0],
                snapshotSequence: ["first", "second"],
                perSnapshotConfig,
            }],
        });
        expect(all.snapshotMigrations.slice(0, 3)).toEqual(prefix.snapshotMigrations);
    });

    it("records reset dependencies and makes the baseline and phase immutable", async () => {
        const output = await new MigrationConfigTransformer().processFromObject(sequenceConfig());
        const resources = buildResolvedMigrationResources(output).resources;
        const deletion = resources.find(r => r.name === "source-target-second-migration-0-deletes")!;
        const additions = resources.find(r => r.name === "source-target-second-migration-0")!;
        const snapshot = resources.find(r => r.name === "source-second")!;
        expect(snapshot.parameters.dependsOn).toEqual(["source-target-first-migration-0"]);
        expect(deletion.parameters.dependsOn).toEqual([
            "source-second", "source-first", "source-target-first-migration-0",
        ]);
        expect(additions.parameters.dependsOn).toEqual([
            "source-second", "source-first", "source-target-second-migration-0-deletes",
        ]);
        expect(dryRunResourcePolicy(deletion, {
            ...deletion,
            parameters: {...deletion.parameters, previousDataSnapshotResourceName: "source-third"},
        }).allowed).toBe(false);
    });

    it("leaves independent snapshots unchanged when no sequence is declared", async () => {
        const config = sequenceConfig();
        const {snapshotSequence: _sequence, ...migrationConfig} = config.snapshotMigrationConfigs[0];
        const output = await new MigrationConfigTransformer().processFromObject({
            ...config, snapshotMigrationConfigs: [migrationConfig],
        });
        expect(output.snapshotSequences).toBeUndefined();
        expect(output.snapshotMigrations).toHaveLength(3);
        for (const migration of output.snapshotMigrations) {
            expect(migration.delta).toBeUndefined();
            expect(migration.sequenceName).toBeUndefined();
        }
        for (const resource of buildResolvedMigrationResources(output).resources.filter(r => r.kind === "SnapshotMigration")) {
            const previousParameters = {...resource.parameters};
            for (const field of ["deltaMode", "previousDataSnapshotResourceName", "previousExternalSnapshotName",
                "previousMigrationResourceName"]) {
                delete previousParameters[field];
                expect(resource.parameters).not.toHaveProperty(field);
            }
            expect(dryRunResourcePolicy({...resource, parameters: previousParameters}, resource).allowed).toBe(true);
        }
    });

    it.each([
        ["duplicates", ["first", "second", "second"]],
        ["unknown snapshot", ["first", "second", "absent"]],
        ["omitted snapshot", ["first", "second"]],
        ["no successor", ["first"]],
    ])("rejects %s before deploying", async (_description, snapshotSequence) => {
        const config = sequenceConfig();
        await expect(new MigrationConfigTransformer().processFromObject({
            ...config,
            snapshotMigrationConfigs: [{...config.snapshotMigrationConfigs[0], snapshotSequence}],
        })).rejects.toThrow();
    });

    it("rejects concurrent migration configurations using the sequence's source or target", async () => {
        const config = sequenceConfig();
        await expect(new MigrationConfigTransformer().processFromObject({
            ...config,
            snapshotMigrationConfigs: [...config.snapshotMigrationConfigs, config.snapshotMigrationConfigs[0]],
        })).rejects.toThrow(/sequence|Sequence/);
    });

    it.each(["none", "first"])(
        "requires replay to wait for the final snapshot instead of %s",
        async dependency => {
            const config = {
                ...sequenceConfig(),
                traffic: {
                    proxies: {capture: {source: "source", proxyConfig: {listenPort: 9200}}},
                    replayers: {replay: {
                        fromCapturedTraffic: "capture",
                        toTarget: "target",
                        dependsOnSnapshotMigrations: dependency === "none" ? [] : [{source: "source", snapshot: dependency}],
                    }},
                },
            };
            await expect(new MigrationConfigTransformer().processFromObject(config))
                .rejects.toThrow(/must depend on its final snapshot/);
            config.traffic.replayers.replay.dependsOnSnapshotMigrations = [{source: "source", snapshot: "third"}];
            const output = await new MigrationConfigTransformer().processFromObject(config);
            const finalPhase = output.snapshotMigrations.find(m => m.label === "third" && m.delta?.mode === "UPDATES_ONLY")!;
            expect(output.trafficReplays?.[0].dependsOnSnapshotMigrations)
                .toEqual(expect.arrayContaining([expect.objectContaining({
                    resourceName: finalPhase.resourceName,
                    configChecksum: finalPhase.checksumForReplayer,
                })]));
        },
    );

    it.each([
        {serverGeneratedIds: "ALWAYS"},
        {enableSourcelessMigrations: true},
        {docTransformerConfig: '{"rename": {}}'},
        {indexAllowlist: ["different-index"]},
    ])("rejects incompatible successor document options: %j", async documentBackfillConfig => {
        const config = sequenceConfig();
        config.snapshotMigrationConfigs[0].perSnapshotConfig.second = [{
            metadataMigrationConfig: {},
            documentBackfillConfig: {...documentBackfillConfig, skipApproval: false},
        }];
        await expect(new MigrationConfigTransformer().processFromObject(config)).rejects.toThrow();
    });
});
