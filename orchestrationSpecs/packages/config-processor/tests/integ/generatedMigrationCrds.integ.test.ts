import {K3sContainer, StartedK3sContainer} from "@testcontainers/k3s";
import {
    generateMigrationCrdsYaml,
    generateValidatingAdmissionPoliciesYaml,
    OVERALL_MIGRATION_CONFIG,
} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {parse as parseYaml, stringify} from "yaml";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {MigrationConfigTransformer, MigrationInitializer} from "../../src";

const TEST_NAMESPACE = "migration-crd-roundtrip";
const MIGRATION_RUN_NAMESPACE = "migration-run-history";
const DELETION_BOOKKEEPING_NAMESPACE = "deletion-bookkeeping";
const FAILED_DOCUMENT_STREAM_NAMESPACE = "failed-document-stream";

// What an AWS deployment provides via the migrations-default-s3-config ConfigMap.
const DEPLOYMENT_DEFAULTS = {
    defaultS3Bucket: "deployment-provisioned-bucket",
    defaultS3Region: "us-east-1",
    defaultS3Endpoint: "https://s3.us-east-1.amazonaws.com",
};

const CRD_KIND_TO_PLURAL: Record<string, string> = {
    ApprovalGate: "approvalgates",
    CaptureProxy: "captureproxies",
    CapturedTraffic: "capturedtraffics",
    DataSnapshot: "datasnapshots",
    KafkaCluster: "kafkaclusters",
    MigrationRun: "migrationruns",
    SnapshotMigration: "snapshotmigrations",
    TrafficReplay: "trafficreplays",
};

function sampleConfig(): z.infer<typeof OVERALL_MIGRATION_CONFIG> {
    return {
        sourceClusters: {
            source: {
                endpoint: "https://source.example.com",
                allowInsecure: true,
                version: "ES 7.10.2",
                snapshotInfo: {
                    repos: {
                        default: {
                            awsRegion: "us-east-2",
                            repoPathUri: "s3://bucket/path",
                        },
                    },
                    snapshots: {
                        snap1: {
                            repoName: "default",
                            config: {
                                createSnapshotConfig: {
                                    snapshotPrefix: "snap1",
                                    compressionEnabled: true,
                                },
                            },
                        },
                    },
                },
            },
        },
        targetClusters: {
            target: {
                endpoint: "https://target.example.com",
                allowInsecure: true,
            },
        },
        traffic: {
            proxies: {
                "source-proxy": {
                    source: "source",
                    proxyConfig: {
                        listenPort: 9200,
                    },
                },
            },
            replayers: {
                replay: {
                    fromCapturedTraffic: "source-proxy",
                    toTarget: "target",
                    dependsOnSnapshotMigrations: [
                        {source: "source", snapshot: "snap1"},
                    ],
                    replayerConfig: {
                        speedupFactor: 5,
                        tupleMaxFileSizeMb: 128,
                    },
                },
            },
        },
        snapshotMigrationConfigs: [{
            fromSource: "source",
            toTarget: "target",
            perSnapshotConfig: {
                snap1: [{
                    metadataMigrationConfig: {},
                    documentBackfillConfig: {
                        maxConnections: 8,
                    },
                }],
            },
        }],
    } as any;
}

async function execOrThrow(container: StartedK3sContainer, command: string[], context: string): Promise<string> {
    const result = await container.exec(command);
    if (result.exitCode !== 0) {
        throw new Error(`${context} failed.\nCommand: ${command.join(" ")}\n${result.output}`);
    }
    return result.output;
}

async function execExpectFailure(container: StartedK3sContainer, command: string[], context: string): Promise<string> {
    const result = await container.exec(command);
    if (result.exitCode === 0) {
        throw new Error(`${context} unexpectedly succeeded.\nCommand: ${command.join(" ")}\n${result.output}`);
    }
    return result.output;
}

async function copyTextToContainer(container: StartedK3sContainer, contents: string, target: string) {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "migration-crd-integ-"));
    const source = path.join(tempDir, path.basename(target));
    fs.writeFileSync(source, contents, "utf8");
    try {
        await container.copyFilesToContainer([{source, target}]);
    } finally {
        fs.rmSync(tempDir, {recursive: true, force: true});
    }
}

function manifestForCreate(item: any) {
    const {status, ...withoutStatus} = item;
    void status;
    return {
        ...withoutStatus,
        metadata: {
            ...withoutStatus.metadata,
            namespace: TEST_NAMESPACE,
        },
    };
}

/** Production initializer path: transform with deployment defaults, write the bundle, read the CR back. */
async function generateSnapshotMigrationManifest(config: z.infer<typeof OVERALL_MIGRATION_CONFIG>) {
    const workflows = await new MigrationConfigTransformer(DEPLOYMENT_DEFAULTS).processFromObject(config);
    const outputDir = fs.mkdtempSync(path.join(os.tmpdir(), "fds-bundle-"));
    try {
        await new MigrationInitializer().generateOutputFiles(
            workflows, outputDir, config, "workflow-a", {runNumber: 1700000000000});
        const resourcesDir = path.join(outputDir, "resources");
        const file = fs.readdirSync(resourcesDir).find(f => f.includes("-snapshotmigration-"));
        if (!file) {
            throw new Error(`No SnapshotMigration manifest in ${resourcesDir}`);
        }
        return parseYaml(fs.readFileSync(path.join(resourcesDir, file), "utf8"));
    } finally {
        fs.rmSync(outputDir, {recursive: true, force: true});
    }
}

function assertSpecRoundTrip(kind: string, name: string, expected: unknown, actual: unknown) {
    try {
        expect(actual).toEqual(expected);
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        throw new Error(`${kind}/${name} spec did not round-trip through the generated CRD.\n${message}`);
    }
}

describe("generated migration CRDs live compatibility", () => {
    let container: StartedK3sContainer;

    beforeAll(async () => {
        container = await new K3sContainer("rancher/k3s:v1.31.6-k3s1")
            .withCommand(["server", "--disable=traefik"])
            .start();
    });

    afterAll(async () => {
        if (container) {
            await container.stop();
        }
    });

    test("accepts custom migration resources generated from a workflow config", async () => {
        await copyTextToContainer(container, generateMigrationCrdsYaml(), "/tmp/migrationCrds.yaml");
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationCrds.yaml"], "apply generated migration CRDs");
        await execOrThrow(
            container,
            [
                "kubectl",
                "wait",
                "--for=condition=Established",
                "--timeout=90s",
                "crd",
                "-l",
                "migrations.opensearch.org/generated=true",
            ],
            "wait for generated migration CRDs"
        );

        await execOrThrow(container, ["kubectl", "create", "namespace", TEST_NAMESPACE], "create test namespace");

        const bundle = await new MigrationInitializer().generateMigrationBundle(sampleConfig(), "workflow-a", {runNumber: 1700000000000});
        const resources = bundle.customMigrationResources.items.map(manifestForCreate);

        for (let i = 0; i < resources.length; i++) {
            const resource = resources[i];
            const name = resource.metadata.name;
            const plural = CRD_KIND_TO_PLURAL[resource.kind];
            if (!plural) {
                throw new Error(`No plural mapping for ${resource.kind}/${name}`);
            }

            const containerPath = `/tmp/resource-${String(i).padStart(2, "0")}.yaml`;
            await copyTextToContainer(container, stringify(resource), containerPath);
            await execOrThrow(
                container,
                ["kubectl", "apply", "-f", containerPath, "-n", TEST_NAMESPACE],
                `apply ${resource.kind}/${name}`
            );

            const readBack = JSON.parse(await execOrThrow(
                container,
                [
                    "kubectl",
                    "get",
                    `${plural}.migrations.opensearch.org/${name}`,
                    "-n",
                    TEST_NAMESPACE,
                    "-o",
                    "json",
                ],
                `read back ${resource.kind}/${name}`
            ));
            assertSpecRoundTrip(resource.kind, name, resource.spec, readBack.spec);
        }
    });

    test("accepts MigrationRun history records and rejects spec updates", async () => {
        await copyTextToContainer(container, generateMigrationCrdsYaml(), "/tmp/migrationCrds.yaml");
        await copyTextToContainer(container, generateValidatingAdmissionPoliciesYaml(), "/tmp/migrationVaps.yaml");
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationCrds.yaml"], "apply generated migration CRDs");
        await execOrThrow(
            container,
            [
                "kubectl",
                "wait",
                "--for=condition=Established",
                "--timeout=90s",
                "crd",
                "-l",
                "migrations.opensearch.org/generated=true",
            ],
            "wait for generated migration CRDs"
        );
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationVaps.yaml"], "apply generated migration VAPs");
        await execOrThrow(container, ["kubectl", "create", "namespace", MIGRATION_RUN_NAMESPACE], "create migration run namespace");

        const migrationRun = {
            apiVersion: "migrations.opensearch.org/v1alpha1",
            kind: "MigrationRun",
            metadata: {
                name: "run-abc123",
                namespace: MIGRATION_RUN_NAMESPACE,
                labels: {
                    "migrations.opensearch.org/workflow-name": "migration-workflow",
                    "migrations.opensearch.org/run-number": "52",
                    "migrations.opensearch.org/year": "2026",
                    "migrations.opensearch.org/month": "05",
                    "migrations.opensearch.org/week": "21",
                },
            },
            spec: {
                workflowName: "migration-workflow",
                runNumber: 52,
                timestamp: "2026-05-18T11:44:15Z",
                resolvedConfig: {source: "https://old-cluster:9200", target: "https://new-cluster:9200"},
            },
        };
        await copyTextToContainer(container, stringify(migrationRun), "/tmp/migrationRun.yaml");
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationRun.yaml"], "apply MigrationRun");
        await execOrThrow(
            container,
            [
                "kubectl",
                "label",
                "migrationruns.migrations.opensearch.org/run-abc123",
                "-n",
                MIGRATION_RUN_NAMESPACE,
                "migrations.opensearch.org/workflow-uid=workflow-uid-123",
            ],
            "set MigrationRun workflow UID label once"
        );
        await execOrThrow(
            container,
            [
                "kubectl",
                "patch",
                "migrationruns.migrations.opensearch.org/run-abc123",
                "-n",
                MIGRATION_RUN_NAMESPACE,
                "--subresource=status",
                "--type=merge",
                "-p",
                JSON.stringify({
                    status: {
                        workflowUid: "workflow-uid-123",
                        workflowCreationTimestamp: "2026-05-18T11:45:00Z",
                    },
                }),
            ],
            "set MigrationRun workflow status once"
        );

        const updateOutput = await execExpectFailure(
            container,
            [
                "kubectl",
                "patch",
                "migrationruns.migrations.opensearch.org/run-abc123",
                "-n",
                MIGRATION_RUN_NAMESPACE,
                "--type=merge",
                "-p",
                JSON.stringify({spec: {runNumber: 53}}),
            ],
            "patch immutable MigrationRun spec"
        );
        expect(updateOutput).toContain("MigrationRun specs are historical records and are immutable after creation.");
        const statusUpdateOutput = await execExpectFailure(
            container,
            [
                "kubectl",
                "patch",
                "migrationruns.migrations.opensearch.org/run-abc123",
                "-n",
                MIGRATION_RUN_NAMESPACE,
                "--subresource=status",
                "--type=merge",
                "-p",
                JSON.stringify({status: {workflowUid: "workflow-uid-456"}}),
            ],
            "patch immutable MigrationRun workflow status"
        );
        expect(statusUpdateOutput).toContain("MigrationRun workflow status fields may only be set once.");

        const readBack = JSON.parse(await execOrThrow(
            container,
            [
                "kubectl",
                "get",
                "migrationruns.migrations.opensearch.org/run-abc123",
                "-n",
                MIGRATION_RUN_NAMESPACE,
                "-o",
                "json",
            ],
            "read back MigrationRun"
        ));
        expect(readBack.spec).toEqual(migrationRun.spec);
        expect(readBack.metadata.labels).toMatchObject(migrationRun.metadata.labels);
        expect(readBack.metadata.labels).toMatchObject({
            "migrations.opensearch.org/workflow-uid": "workflow-uid-123",
        });
        expect(readBack.status).toMatchObject({
            workflowUid: "workflow-uid-123",
            workflowCreationTimestamp: "2026-05-18T11:45:00Z",
        });
    });

    test("allows Kubernetes deletion bookkeeping after a migration resource enters Deleting", async () => {
        await copyTextToContainer(container, generateMigrationCrdsYaml(), "/tmp/migrationCrds.yaml");
        await copyTextToContainer(container, generateValidatingAdmissionPoliciesYaml(), "/tmp/migrationVaps.yaml");
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationCrds.yaml"], "apply generated migration CRDs");
        await execOrThrow(
            container,
            [
                "kubectl",
                "wait",
                "--for=condition=Established",
                "--timeout=90s",
                "crd",
                "-l",
                "migrations.opensearch.org/generated=true",
            ],
            "wait for generated migration CRDs"
        );
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationVaps.yaml"], "apply generated migration VAPs");
        await execOrThrow(container, ["kubectl", "create", "namespace", DELETION_BOOKKEEPING_NAMESPACE], "create deletion bookkeeping namespace");

        const dataSnapshot = {
            apiVersion: "migrations.opensearch.org/v1alpha1",
            kind: "DataSnapshot",
            metadata: {
                name: "deleting-snapshot",
                namespace: DELETION_BOOKKEEPING_NAMESPACE,
                finalizers: ["migrations.opensearch.org/test-finalizer"],
            },
            spec: {
                snapshotPrefix: "deleting-snapshot",
                indexAllowlist: [],
                maxSnapshotRateMbPerNode: 0,
                jvmArgs: "",
                loggingConfigurationOverrideConfigMap: "",
            },
        };
        await copyTextToContainer(container, stringify(dataSnapshot), "/tmp/deletingDataSnapshot.yaml");
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/deletingDataSnapshot.yaml"], "apply deleting DataSnapshot");
        await execOrThrow(
            container,
            [
                "kubectl",
                "patch",
                "datasnapshots.migrations.opensearch.org/deleting-snapshot",
                "-n",
                DELETION_BOOKKEEPING_NAMESPACE,
                "--subresource=status",
                "--type=merge",
                "-p",
                JSON.stringify({status: {phase: "Deleting"}}),
            ],
            "mark DataSnapshot as Deleting"
        );
        await execOrThrow(
            container,
            [
                "kubectl",
                "delete",
                "datasnapshots.migrations.opensearch.org/deleting-snapshot",
                "-n",
                DELETION_BOOKKEEPING_NAMESPACE,
                "--cascade=foreground",
                "--wait=false",
            ],
            "request foreground deletion for DataSnapshot"
        );

        await execOrThrow(
            container,
            [
                "kubectl",
                "patch",
                "datasnapshots.migrations.opensearch.org/deleting-snapshot",
                "-n",
                DELETION_BOOKKEEPING_NAMESPACE,
                "--type=merge",
                "-p",
                JSON.stringify({metadata: {finalizers: []}}),
            ],
            "remove deletion finalizer from Deleting DataSnapshot"
        );
        await execOrThrow(
            container,
            [
                "kubectl",
                "wait",
                "--for=delete",
                "--timeout=30s",
                "datasnapshots.migrations.opensearch.org/deleting-snapshot",
                "-n",
                DELETION_BOOKKEEPING_NAMESPACE,
            ],
            "wait for DataSnapshot deletion after finalizer removal"
        );
    });

    test("leaves the failed document stream off when the config names no bucket", async () => {
        await copyTextToContainer(container, generateMigrationCrdsYaml(), "/tmp/migrationCrds.yaml");
        await execOrThrow(container, ["kubectl", "apply", "-f", "/tmp/migrationCrds.yaml"], "apply generated migration CRDs");
        await execOrThrow(
            container,
            [
                "kubectl",
                "wait",
                "--for=condition=Established",
                "--timeout=90s",
                "crd",
                "-l",
                "migrations.opensearch.org/generated=true",
            ],
            "wait for generated migration CRDs"
        );
        await execOrThrow(container, ["kubectl", "create", "namespace", FAILED_DOCUMENT_STREAM_NAMESPACE],
            "create failed document stream namespace");

        const applyAndReadSpec = async (manifest: any, label: string) => {
            const named = {
                ...manifest,
                metadata: {...manifest.metadata, name: label, namespace: FAILED_DOCUMENT_STREAM_NAMESPACE},
            };
            await copyTextToContainer(container, stringify(named), `/tmp/sm-${label}.yaml`);
            await execOrThrow(
                container,
                ["kubectl", "apply", "-f", `/tmp/sm-${label}.yaml`, "-n", FAILED_DOCUMENT_STREAM_NAMESPACE],
                `apply SnapshotMigration/${label}`
            );
            const readBack = JSON.parse(await execOrThrow(
                container,
                ["kubectl", "get", `snapshotmigrations.migrations.opensearch.org/${label}`,
                    "-n", FAILED_DOCUMENT_STREAM_NAMESPACE, "-o", "json"],
                `read back SnapshotMigration/${label}`
            ));
            return readBack.spec;
        };

        const withoutBucket = await applyAndReadSpec(
            await generateSnapshotMigrationManifest(sampleConfig()), "no-bucket");

        // The deployment default must not leak in.
        expect(withoutBucket.documentBackfillFailedDocumentStreamS3Bucket).toBeUndefined();
        expect(withoutBucket.documentBackfillFailedDocumentStreamS3Region).toBeUndefined();
        expect(withoutBucket.documentBackfillFailedDocumentStreamS3Endpoint).toBeUndefined();
        expect(JSON.stringify(withoutBucket)).not.toContain(DEPLOYMENT_DEFAULTS.defaultS3Bucket);

        // Control: an explicit bucket does reach the CR, so the assertions above aren't vacuous.
        const configWithBucket = sampleConfig() as any;
        configWithBucket.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig
            .failedDocumentStreamS3Bucket = "user-named-bucket";
        const withBucket = await applyAndReadSpec(
            await generateSnapshotMigrationManifest(configWithBucket), "with-bucket");

        expect(withBucket.documentBackfillFailedDocumentStreamS3Bucket).toBe("user-named-bucket");
        expect(withBucket.documentBackfillFailedDocumentStreamS3Region).toBe("us-east-2");   // snapshot repo's
    });
});
