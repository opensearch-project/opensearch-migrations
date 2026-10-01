import {MigrationConfigTransformer} from "../src/migrationConfigTransformer";
import {MigrationInitializer} from "../src/migrationInitializer";
import {evaluateBackfillRepeat, evaluateBackfillRepeatRequest} from "../src/evaluateBackfillRepeat";
import * as fs from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {parse} from "yaml";

function policyConfig(maxRuns = 3, snapshotLag?: string) {
    return {
        skipApprovals: true,
        sourceClusters: {
            source: {
                endpoint: "http://source:9200",
                version: "ES 7.10",
                snapshotInfo: {
                    repos: {repo: {repoPathUri: "s3://snapshots/repeat", awsRegion: "us-east-1"}},
                },
            },
        },
        targetClusters: {target: {endpoint: "http://target:9200"}},
        snapshotMigrationConfigs: [{
            fromSource: "source",
            toTarget: "target",
            backfill: {
                snapshot: {repoName: "repo", createSnapshotConfig: {indexAllowlist: ["orders"]}},
                metadataMigrationConfig: {},
                documentBackfillConfig: {indexAllowlist: ["orders"]},
                repeat: {maxRuns, ...(snapshotLag ? {until: {snapshotLag}} : {})},
            },
        }],
    };
}

describe("declarative repeated backfills", () => {
    it("generates the snapshots and delete/add phases from one set of options", async () => {
        const output = await new MigrationConfigTransformer().processFromObject(policyConfig(3, "2m"));
        expect(output.snapshots[0].createSnapshotConfig.map(snapshot => snapshot.label))
            .toEqual(["backfill-1", "backfill-2", "backfill-3"]);
        const plan = output.snapshotSequences![0];
        expect(plan.repeat).toEqual({maxRuns: 3, snapshotLagTargetSeconds: 120});
        expect(plan.steps.map(step => step.completedRun)).toEqual([1, undefined, 2, undefined, 3]);
        expect(plan.steps.map(step => output.snapshotMigrations[step.migrationIndex].delta?.mode))
            .toEqual([undefined, "DELETES_ONLY", "UPDATES_ONLY", "DELETES_ONLY", "UPDATES_ONLY"]);
    });

    it("allows one run and preserves existing resource identities when the budget increases", async () => {
        const transformer = new MigrationConfigTransformer();
        const once = await transformer.processFromObject(policyConfig(1));
        const more = await transformer.processFromObject(policyConfig(3));
        expect(once.snapshotSequences![0].steps).toHaveLength(1);
        expect(more.snapshotMigrations[0]).toEqual(once.snapshotMigrations[0]);
        expect(more.snapshots[0].createSnapshotConfig[0]).toEqual(once.snapshots[0].createSnapshotConfig[0]);
    });

    it("preserves resource identities and checksums for omitted versus empty snapshot options", async () => {
        const config = policyConfig();
        const migration = config.snapshotMigrationConfigs[0];
        const withoutOptions = {
            ...migration,
            backfill: {
                ...migration.backfill,
                snapshot: {repoName: migration.backfill.snapshot.repoName},
            },
        };
        const transformer = new MigrationConfigTransformer();
        const omitted = await transformer.processFromObject({
            ...config, snapshotMigrationConfigs: [withoutOptions],
        });
        const explicit = await transformer.processFromObject({
            ...config,
            snapshotMigrationConfigs: [{
                ...withoutOptions,
                backfill: {
                    ...withoutOptions.backfill,
                    snapshot: {...withoutOptions.backfill.snapshot, createSnapshotConfig: {}},
                },
            }],
        });

        expect(explicit).toEqual(omitted);
    });

    it("automatically runs a target's replay after its backfill policy, without a generated snapshot reference", async () => {
        const output = await new MigrationConfigTransformer().processFromObject({
            ...policyConfig(3, "2m"),
            traffic: {
                proxies: {capture: {source: "source", proxyConfig: {listenPort: 9200}}},
                replayers: {replay: {fromCapturedTraffic: "capture", toTarget: "target"}},
            },
        });
        expect(output.snapshotSequences![0].replayIndices).toEqual([0]);
        expect(output.trafficReplays[0].snapshotSequenceName).toBe(output.snapshotSequences![0].name);
        expect(output.trafficReplays[0].dependsOn).toEqual(expect.arrayContaining(
            output.snapshotMigrations.map(migration => migration.resourceName),
        ));
    });

    it.each([0, -1, 1.5, 1001])("rejects invalid run budgets: %s", async maxRuns => {
        await expect(new MigrationConfigTransformer().processFromObject(policyConfig(maxRuns))).rejects.toThrow();
    });

    it.each(["0s", "-1m", "two minutes", "2", "Infinity", "9007199254740992d"])(
        "rejects invalid lag thresholds: %s", async lag => {
            await expect(new MigrationConfigTransformer().processFromObject(policyConfig(3, lag))).rejects.toThrow();
        },
    );

    it("rejects combining a policy with manually named snapshots or per-snapshot settings", async () => {
        const config = policyConfig();
        await expect(new MigrationConfigTransformer().processFromObject({
            ...config,
            snapshotMigrationConfigs: [{
                ...config.snapshotMigrationConfigs[0], perSnapshotConfig: {},
            }],
        })).rejects.toThrow(/backfill|perSnapshotConfig/);
        await expect(new MigrationConfigTransformer().processFromObject({
            ...config,
            sourceClusters: {source: {
                ...config.sourceClusters.source,
                snapshotInfo: {
                    ...config.sourceClusters.source.snapshotInfo,
                    snapshots: {named: {repoName: "repo", config: {createSnapshotConfig: {}}}},
                },
            }},
        })).rejects.toThrow(/snapshot|backfill/);
    });

    it("retains source authentication and repository validation for generated snapshots", async () => {
        const config = policyConfig();
        await expect(new MigrationConfigTransformer().processFromObject({
            ...config,
            sourceClusters: {source: {
                ...config.sourceClusters.source, endpoint: "https://source.us-east-1.es.amazonaws.com",
            }},
        })).rejects.toThrow(/SigV4/);
        config.snapshotMigrationConfigs[0].backfill.snapshot.repoName = "missing";
        await expect(new MigrationConfigTransformer().processFromObject(config)).rejects.toThrow(/repo/);
    });
});

describe("repeated backfill initialization", () => {
    const runOptions = {runNumber: 1, timestamp: new Date("2026-09-29T12:00:00Z")};
    const parallelKeys = [
        "snapshot-modern-source-backfill-1",
        "snapshot-modern-source-backfill-2",
        "snapshot-modern-source-backfill-3",
    ];

    it.each([
        {version: "ES 7.10", maxRuns: 3, serialize: undefined, keys: ["snapshot-legacy-source"]},
        {version: "OS 2.19", maxRuns: 4, serialize: undefined, keys: [...parallelKeys, "snapshot-modern-source-backfill-4"]},
        {version: "ES 7.10", maxRuns: 3, serialize: false, keys: parallelKeys},
        {version: "OS 2.19", maxRuns: 4, serialize: true, keys: ["snapshot-legacy-source"]},
    ])("initializes $maxRuns runs for $version (serialize=$serialize)", async ({version, maxRuns, serialize, keys}) => {
        const config = policyConfig(maxRuns);
        const source = config.sourceClusters.source;
        const bundle = await new MigrationInitializer().generateMigrationBundle({
            ...config,
            sourceClusters: {source: {
                ...source,
                version,
                snapshotInfo: {...source.snapshotInfo, serializeSnapshotCreation: serialize},
            }},
        }, "policy-test", runOptions);

        expect(bundle.concurrencyConfigMaps.items[0].data)
            .toEqual(Object.fromEntries(keys.map(key => [key, "1"])));
        expect(bundle.workflows.snapshotSequences![0].steps.filter(step => step.completedRun !== undefined))
            .toHaveLength(maxRuns);
        const migrationRun = bundle.customMigrationResources.items.find(item => item.kind === "MigrationRun");
        expect(migrationRun).toBeDefined();
        expect(Buffer.byteLength(JSON.stringify(migrationRun), "utf8")).toBeLessThan(1024 * 1024);
    });

    it("writes generated semaphore keys when only the transformed configuration is supplied", async () => {
        const workflows = await new MigrationConfigTransformer().processFromObject(policyConfig(4));
        const outputDir = await fs.mkdtemp(join(tmpdir(), "backfill-initializer-"));
        try {
            await new MigrationInitializer().generateOutputFiles(
                workflows, outputDir, null, "policy-test", runOptions,
            );
            const resourceDir = join(outputDir, "resources");
            const file = (await fs.readdir(resourceDir))
                .find(name => name.endsWith("-configmap-concurrency-config.yaml"));
            expect(file).toBeDefined();
            const configMap = parse(await fs.readFile(join(resourceDir, file!), "utf8"));
            expect(configMap.data).toEqual({"snapshot-legacy-source": "1"});
        } finally {
            await fs.rm(outputDir, {recursive: true, force: true});
        }
    });

    it("rejects an oversized budget with instructions to reduce maxRuns", async () => {
        await expect(new MigrationInitializer()
            .generateMigrationBundle(policyConfig(1000), "policy-test", runOptions)
            .then(() => undefined))
            .rejects.toThrow(/MigrationRun.*1 MiB.*[Rr]educe.*backfill\.repeat\.maxRuns/);
    });

    it("rejects oversized transformed plans before writing manifests or apply scripts", async () => {
        const workflows = await new MigrationConfigTransformer().processFromObject(policyConfig(128));
        const parentDir = await fs.mkdtemp(join(tmpdir(), "backfill-size-limit-"));
        try {
            await expect(new MigrationInitializer().generateOutputFiles(
                workflows, join(parentDir, "bundle"), null, "policy-test", runOptions,
            )).rejects.toThrow(/MigrationRun.*1 MiB.*[Rr]educe.*backfill\.repeat\.maxRuns/);
            expect(await fs.readdir(parentDir)).toEqual([]);
        } finally {
            await fs.rm(parentDir, {recursive: true, force: true});
        }
    }, 15000);
});

const started = Date.parse("2026-09-29T12:00:00Z");
const completed = "2026-09-29T12:02:00Z";

describe("backfill repeat decisions", () => {
    it("counts the initial backfill and stops exactly at the run limit", () => {
        expect(evaluateBackfillRepeat({maxRuns: 3}, 1, started, completed).action).toBe("continue");
        expect(evaluateBackfillRepeat({maxRuns: 3}, 3, started, completed))
            .toMatchObject({action: "stop", reason: "runLimitReached", run: 3, snapshotLagSeconds: 120});
    });

    it("stops early on a lag target, including an exactly equal lag", () => {
        expect(evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 120}, 1, started, completed))
            .toMatchObject({action: "stop", reason: "lagTargetMet", run: 1});
        expect(evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 119}, 1, started, completed).action)
            .toBe("continue");
    });

    it("reports an unmet target when the last allowed round finishes", () => {
        expect(evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 119}, 3, started, completed))
            .toMatchObject({action: "fail", reason: "lagTargetNotMet"});
    });

    it("uses persisted timestamps, independent of approval delays and the current clock", () => {
        const before = evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 120}, 1, started, completed);
        jest.useFakeTimers().setSystemTime(new Date("2027-01-01"));
        try {
            expect(evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 120}, 1, started, completed, before))
                .toEqual(before);
            expect(evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 120}, 1, started,
                "2027-01-01T00:00:00Z", before)).toEqual(before);
        } finally {
            jest.useRealTimers();
        }
    });

    it("requires successful backfill and approval, and protects its status patch against a concurrent reset", () => {
        const request = {
            policy: {maxRuns: 3}, run: 1, resourceUid: "migration-uid", configChecksum: "checksum",
            migration: {
                metadata: {uid: "migration-uid", resourceVersion: "42"},
                spec: {dataSnapshotResourceName: "source-backfill-1"},
                status: {
                    configChecksum: "checksum", sequenceCompletionChecksum: "checksum",
                    documentBackfill: {phase: "Completed", updatedAt: completed},
                },
            },
            snapshot: {
                metadata: {name: "source-backfill-1"},
                status: {phase: "Completed", snapshotStartTimeMillis: started},
            },
        };
        const result = evaluateBackfillRepeatRequest(request);
        expect(result.patch.slice(0, 2)).toEqual([
            {op: "test", path: "/metadata/uid", value: "migration-uid"},
            {op: "test", path: "/metadata/resourceVersion", value: "42"},
        ]);
        expect(() => evaluateBackfillRepeatRequest({...request, resourceUid: "reset-uid"})).toThrow();
        request.migration.status.sequenceCompletionChecksum = "";
        expect(() => evaluateBackfillRepeatRequest(request)).toThrow();
        request.migration.status.sequenceCompletionChecksum = "checksum";
        request.migration.status.documentBackfill.phase = "CompletedWithErrors";
        expect(() => evaluateBackfillRepeatRequest(request)).toThrow();
    });

    it("allows increasing the run limit, but rejects changing the lag target or reducing a started budget", () => {
        const previous = evaluateBackfillRepeat({maxRuns: 1}, 1, started, completed);
        expect(evaluateBackfillRepeat({maxRuns: 3}, 1, started, completed, previous).action).toBe("continue");
        expect(() => evaluateBackfillRepeat({maxRuns: 1, snapshotLagTargetSeconds: 120}, 1, started, completed, previous))
            .toThrow(/reset/i);
        const larger = evaluateBackfillRepeat({maxRuns: 3}, 1, started, completed);
        expect(() => evaluateBackfillRepeat({maxRuns: 2}, 1, started, completed, larger)).toThrow(/reset/i);
    });

    it.each([
        [0, completed],
        [Number.NaN, completed],
        [started, "not-a-timestamp"],
        [started + 180_000, completed],
    ])("fails closed on missing or inconsistent timing data", (start, finish) => {
        expect(() => evaluateBackfillRepeat({maxRuns: 3, snapshotLagTargetSeconds: 120}, 1,
            start as number, finish as string)).toThrow();
    });
});
