import {describe, expect, it} from "@jest/globals";
import {promises as fs} from "fs";
import * as os from "os";
import * as path from "path";
import {OVERALL_MIGRATION_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {
    buildLooseResolvedMigrationResources,
    buildResolvedMigrationResources,
    dryRunResourcePolicy,
    MigrationConfigTransformer,
    MigrationInitializer,
    ResolvedMigrationResource,
} from "../src";
import {main as resolveMigrationResourcesMain} from "../src/resolveMigrationResources";

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
                            s3RepoPathUri: "s3://bucket/path",
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

describe("resolved migration resources", () => {
    it("extracts resolved resource parameters from transformed workflow config", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(sampleConfig());
        const resolvedMigrationResources = buildResolvedMigrationResources(workflowConfig, "workflow-a");

        expect(resolvedMigrationResources.workflowName).toBe("workflow-a");
        expect(resolvedMigrationResources.resources).toMatchSnapshot();
        expect(resolvedMigrationResources.resources.every(resource =>
            resource.parameterPolicies === undefined
        )).toBe(true);
    });

    it("omits capture proxy workflow-only file source fields from generated custom resources", async () => {
        const config = sampleConfig();
        config.traffic!.proxies!["source-proxy"].proxyConfig.tls = {
            mode: "existingSecret",
            secretName: "proxy-tls",
            clientAuth: {
                trustedClientCaFile: {
                    configMap: "trusted-client-roots",
                    path: "ca.crt",
                },
            },
        };

        const workflowConfig = await new MigrationConfigTransformer().processFromObject(config);
        const transformedProxyConfig = workflowConfig.proxies![0].proxyConfig;
        expect(transformedProxyConfig.fileSourceVolumes).toHaveLength(1);
        expect(transformedProxyConfig.fileSourceVolumeMounts).toHaveLength(1);
        expect(transformedProxyConfig.sslTrustCertFile).toMatch(/\/ca\.crt$/);

        const resolvedMigrationResources = buildResolvedMigrationResources(workflowConfig, "workflow-a");
        const proxyResource = resolvedMigrationResources.resources.find(resource =>
            resource.kind === "CaptureProxy" && resource.name === "source-proxy");

        expect(proxyResource?.annotations).toEqual(expect.objectContaining({
            "migrations.opensearch.org/workflow-only-fields":
                "fileSourceVolumes,fileSourceVolumeMounts,sslTrustCertFile,requireClientAuth",
            "migrations.opensearch.org/workflow-only-hash": expect.stringMatching(/^sha256:[a-f0-9]{64}$/),
            "migrations.opensearch.org/file-source-refs": JSON.stringify([{
                configMap: {name: "trusted-client-roots"},
                paths: ["ca.crt"],
            }]),
        }));
        // clientAuth stays inside spec.tls (gated subtree); only the flat resolved
        // bridge fields are stripped from the custom resource.
        expect(proxyResource?.parameters).toEqual(expect.objectContaining({
            dependsOn: ["source-proxy-topic"],
            tls: {
                mode: "existingSecret",
                secretName: "proxy-tls",
                clientAuth: {
                    required: true,
                    trustedClientCaFile: {
                        configMap: "trusted-client-roots",
                        path: "ca.crt",
                    },
                },
            },
        }));
        expect(proxyResource?.parameters).not.toHaveProperty("fileSourceVolumes");
        expect(proxyResource?.parameters).not.toHaveProperty("fileSourceVolumeMounts");
        expect(proxyResource?.parameters).not.toHaveProperty("sslTrustCertFile");
        expect(proxyResource?.parameters).not.toHaveProperty("sslTrustCertPem");
        expect(proxyResource?.parameters).not.toHaveProperty("sslTrustCertPemEnvVar");
        expect(proxyResource?.parameters).not.toHaveProperty("requireClientAuth");

        const bundle = await new MigrationInitializer().generateMigrationBundle(
            config,
            "workflow-a",
            {runNumber: 1700000000000}
        );
        const proxyCr = bundle.customMigrationResources.items.find((resource: any) =>
            resource.kind === "CaptureProxy" && resource.metadata.name === "source-proxy");
        expect(proxyCr.metadata.annotations).toEqual(proxyResource?.annotations);
        expect(proxyCr.spec).toEqual(proxyResource?.parameters);
    });

    it("includes policy metadata only when debug metadata is requested", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(sampleConfig());
        const resolvedMigrationResources = buildResolvedMigrationResources(
            workflowConfig,
            "workflow-a",
            {includeParameterPolicies: true}
        );

        const replay = resolvedMigrationResources.resources.find(resource =>
            resource.kind === "TrafficReplay" && resource.name === "source-proxy-target-replay");
        expect(replay?.parameterPolicies).toEqual(expect.arrayContaining([
            expect.objectContaining({
                specPath: ["speedupFactor"],
                changeRestriction: "safe",
            }),
            expect.objectContaining({
                specPath: ["tupleMaxFileSizeMb"],
                changeRestriction: "gated",
            }),
        ]));
    });

    it("dry-runs VAP-style decisions for safe, gated, impossible, and invariant changes", () => {
        const replayBefore: ResolvedMigrationResource = {
            apiVersion: "migrations.opensearch.org/v1alpha1",
            kind: "TrafficReplay",
            name: "replay",
            parameters: {speedupFactor: 4, tupleMaxFileSizeMb: 128},
            parameterPolicies: [],
        };
        const replayAfter: ResolvedMigrationResource = {
            ...replayBefore,
            parameters: {speedupFactor: 5, tupleMaxFileSizeMb: 256},
        };

        const unapprovedReplay = dryRunResourcePolicy(replayBefore, replayAfter);
        expect(unapprovedReplay.allowed).toBe(false);
        expect(unapprovedReplay.changes).toContainEqual(expect.objectContaining({
            path: "speedupFactor",
            result: "allowed",
        }));
        expect(unapprovedReplay.changes).toContainEqual(expect.objectContaining({
            path: "tupleMaxFileSizeMb",
            result: "approval-required",
        }));

        expect(dryRunResourcePolicy(replayBefore, replayAfter, {approved: true}).allowed).toBe(true);

        const kafkaBefore: ResolvedMigrationResource = {
            apiVersion: "migrations.opensearch.org/v1alpha1",
            kind: "KafkaCluster",
            name: "default",
            parameters: {auth: {type: "scram-sha-512"}},
            parameterPolicies: [],
        };
        const kafkaAfter = {
            ...kafkaBefore,
            parameters: {auth: {type: "none"}},
        };
        expect(dryRunResourcePolicy(kafkaBefore, kafkaAfter).changes).toContainEqual(expect.objectContaining({
            path: "auth.type",
            result: "blocked",
        }));

        const trafficBefore: ResolvedMigrationResource = {
            apiVersion: "migrations.opensearch.org/v1alpha1",
            kind: "CapturedTraffic",
            name: "topic",
            parameters: {partitions: 3},
            parameterPolicies: [],
        };
        const trafficAfter = {
            ...trafficBefore,
            parameters: {partitions: 2},
        };
        expect(dryRunResourcePolicy(trafficBefore, trafficAfter, {approved: true}).changes).toContainEqual(expect.objectContaining({
            path: "partitions",
            result: "blocked",
            message: "partitions cannot decrease.",
        }));
    });

    it("writes resolvedMigrationResources.json next to workflow outputs for local inspection", async () => {
        const initializer = new MigrationInitializer();
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(sampleConfig());
        const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "resolved-migration-resources-test-"));

        await initializer.generateOutputFiles(workflowConfig, outputDir, sampleConfig(), "workflow-a", {runNumber: 1700000000000});

        const resolvedMigrationResources = JSON.parse(
            await fs.readFile(path.join(outputDir, "resolvedMigrationResources.json"), "utf8")
        );
        expect(resolvedMigrationResources.workflowName).toBe("workflow-a");
        expect(resolvedMigrationResources.resources).toEqual(expect.arrayContaining([
            expect.objectContaining({
                kind: "TrafficReplay",
                name: "source-proxy-target-replay",
                parameters: expect.objectContaining({speedupFactor: 5}),
            }),
        ]));
        expect(resolvedMigrationResources.resources.every((resource: any) =>
            resource.parameterPolicies === undefined
        )).toBe(true);
    });

    it("builds resolved migration resources from a transformed workflow config file", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(sampleConfig());
        const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "resolved-migration-resources-cli-test-"));
        const inputFile = path.join(outputDir, "workflowMigration.config.yaml");
        const outputFile = path.join(outputDir, "resolvedMigrationResources.json");
        await fs.writeFile(inputFile, JSON.stringify(workflowConfig, null, 2));

        await resolveMigrationResourcesMain([
            "--transformed-config", inputFile,
            "--workflow-name", "workflow-from-cli",
            "--output", outputFile,
        ]);

        const resolvedMigrationResources = JSON.parse(await fs.readFile(outputFile, "utf8"));
        expect(resolvedMigrationResources.workflowName).toBe("workflow-from-cli");
        expect(resolvedMigrationResources.resources).toContainEqual(expect.objectContaining({
            kind: "TrafficReplay",
            name: "source-proxy-target-replay",
            parameters: expect.objectContaining({speedupFactor: 5}),
        }));
        expect(resolvedMigrationResources.resources.every((resource: any) =>
            resource.parameterPolicies === undefined
        )).toBe(true);
    });

    it("can include parameter policies from the command line for debugging", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(sampleConfig());
        const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "resolved-migration-resources-cli-test-"));
        const inputFile = path.join(outputDir, "workflowMigration.config.yaml");
        const outputFile = path.join(outputDir, "resolvedMigrationResources.json");
        await fs.writeFile(inputFile, JSON.stringify(workflowConfig, null, 2));

        await resolveMigrationResourcesMain([
            "--transformed-config", inputFile,
            "--workflow-name", "workflow-from-cli",
            "--include-parameter-policies",
            "--output", outputFile,
        ]);

        const resolvedMigrationResources = JSON.parse(await fs.readFile(outputFile, "utf8"));
        const replay = resolvedMigrationResources.resources.find((resource: any) =>
            resource.kind === "TrafficReplay" && resource.name === "source-proxy-target-replay");
        expect(replay.parameterPolicies).toEqual(expect.arrayContaining([
            expect.objectContaining({
                specPath: ["tupleMaxFileSizeMb"],
                changeRestriction: "gated",
            }),
        ]));
    });

    it("loosely projects incomplete user config for the manage resource view", async () => {
        const config = sampleConfig();
        delete (config.sourceClusters.source as any).endpoint;
        delete (config.traffic!.proxies!["source-proxy"] as any).proxyConfig;

        const resolved = await buildLooseResolvedMigrationResources(config, "workflow-a");

        expect(resolved.projectionMode).toBe("loose");
        expect(resolved.projectionComplete).toBe(false);
        expect(resolved.validation?.valid).toBe(false);
        expect(resolved.resources).toContainEqual(expect.objectContaining({
            kind: "CaptureProxy",
            name: "source-proxy",
            parameters: {dependsOn: ["source-proxy-topic"]},
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    path: ["traffic", "proxies", "source-proxy", "proxyConfig"],
                }),
            ]),
        }));
        expect(resolved.resources).toContainEqual(expect.objectContaining({
            kind: "CapturedTraffic",
            name: "source-proxy-topic",
            parameters: expect.objectContaining({
                kafkaClusterName: "default",
                topicName: "source-proxy",
            }),
        }));
        expect(resolved.consoleResources?.sources).toContainEqual(expect.objectContaining({
            refName: "source",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    path: ["sourceClusters", "source", "endpoint"],
                }),
            ]),
        }));
    });

    it("returns best-effort resources from the loose CLI without exiting on validation errors", async () => {
        const config = sampleConfig();
        delete (config.traffic!.proxies!["source-proxy"] as any).proxyConfig;
        const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "resolved-migration-resources-loose-cli-test-"));
        const inputFile = path.join(outputDir, "workflowMigration.config.yaml");
        const outputFile = path.join(outputDir, "resolvedMigrationResources.json");
        await fs.writeFile(inputFile, JSON.stringify(config, null, 2));

        await resolveMigrationResourcesMain([
            "--user-config", inputFile,
            "--workflow-name", "workflow-from-cli",
            "--validation-mode", "loose",
            "--output", outputFile,
        ]);

        const resolved = JSON.parse(await fs.readFile(outputFile, "utf8"));
        expect(resolved.validation.valid).toBe(false);
        expect(resolved.resources).toContainEqual(expect.objectContaining({
            kind: "CaptureProxy",
            name: "source-proxy",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({path: ["traffic", "proxies", "source-proxy", "proxyConfig"]}),
            ]),
        }));
    });
});
