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

async function transformAndResolve(config: z.infer<typeof OVERALL_MIGRATION_CONFIG>) {
    const workflowConfig = await new MigrationConfigTransformer().processFromObject(config);
    const resolvedMigrationResources = buildResolvedMigrationResources(workflowConfig, "workflow-a");
    const resource = (kind: string, name: string) => {
        const match = resolvedMigrationResources.resources.find(r => r.kind === kind && r.name === name);
        if (!match) {
            throw new Error(`Resource ${kind}/${name} not found`);
        }
        return match;
    };
    const singleResource = (kind: string) => {
        const matches = resolvedMigrationResources.resources.filter(r => r.kind === kind);
        if (matches.length !== 1) {
            throw new Error(`Expected one ${kind} resource, found ${matches.length}`);
        }
        return matches[0];
    };
    return {workflowConfig, resolvedMigrationResources, resource, singleResource};
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

    it("makes source, target, and Kafka identity checksum changes visible in resolved CR specs", async () => {
        const sourceBefore = await transformAndResolve(sampleConfig());
        const sourceConfigAfter = sampleConfig();
        sourceConfigAfter.sourceClusters.source.endpoint = "https://source-b.example.com";
        const sourceAfter = await transformAndResolve(sourceConfigAfter);

        expect(sourceBefore.workflowConfig.proxies[0].configChecksum)
            .not.toBe(sourceAfter.workflowConfig.proxies[0].configChecksum);
        expect(sourceBefore.resource("CaptureProxy", "source-proxy").parameters.sourceEndpoint)
            .toBe("https://source.example.com");
        expect(sourceAfter.resource("CaptureProxy", "source-proxy").parameters.sourceEndpoint)
            .toBe("https://source-b.example.com");

        const targetBefore = await transformAndResolve(sampleConfig());
        const targetConfigAfter = sampleConfig();
        targetConfigAfter.targetClusters.target.endpoint = "https://target-b.example.com";
        const targetAfter = await transformAndResolve(targetConfigAfter);

        expect(targetBefore.workflowConfig.snapshotMigrations[0].configChecksum)
            .not.toBe(targetAfter.workflowConfig.snapshotMigrations[0].configChecksum);
        expect(targetBefore.workflowConfig.trafficReplays[0].configChecksum)
            .not.toBe(targetAfter.workflowConfig.trafficReplays[0].configChecksum);
        expect(targetBefore.singleResource("SnapshotMigration").parameters.targetEndpoint)
            .toBe("https://target.example.com");
        expect(targetAfter.singleResource("SnapshotMigration").parameters.targetEndpoint)
            .toBe("https://target-b.example.com");
        expect(targetBefore.resource("TrafficReplay", "replay").parameters.targetEndpoint)
            .toBe("https://target.example.com");
        expect(targetAfter.resource("TrafficReplay", "replay").parameters.targetEndpoint)
            .toBe("https://target-b.example.com");

        const kafkaConfigBefore = sampleConfig();
        kafkaConfigBefore.traffic = {
            ...kafkaConfigBefore.traffic,
            kafkaClusters: {
            default: {existing: {kafkaConnection: "broker-a:9092"}},
            },
        } as any;
        const kafkaBefore = await transformAndResolve(kafkaConfigBefore);
        const kafkaConfigAfter = sampleConfig();
        kafkaConfigAfter.traffic = {
            ...kafkaConfigAfter.traffic,
            kafkaClusters: {
            default: {existing: {kafkaConnection: "broker-b:9092"}},
            },
        } as any;
        const kafkaAfter = await transformAndResolve(kafkaConfigAfter);

        expect(kafkaBefore.workflowConfig.proxies[0].topicConfigChecksum)
            .not.toBe(kafkaAfter.workflowConfig.proxies[0].topicConfigChecksum);
        expect(kafkaBefore.workflowConfig.trafficReplays[0].fromCapturedTrafficConfigChecksum)
            .not.toBe(kafkaAfter.workflowConfig.trafficReplays[0].fromCapturedTrafficConfigChecksum);
        expect(kafkaBefore.resource("CapturedTraffic", "source-proxy-topic").parameters.kafkaBrokers)
            .toBe("broker-a:9092");
        expect(kafkaAfter.resource("CapturedTraffic", "source-proxy-topic").parameters.kafkaBrokers)
            .toBe("broker-b:9092");
    });

    it("emits empty-string defaults for default-less fields so resolved params match the applied CR spec", async () => {
        // The apply manifests always write these default-less string fields with "" (expr.dig(..., "")).
        // The resolved parameters must emit the same keys so MigrationRun history / the dry-run preview
        // match the spec actually applied to the live CR, rather than silently omitting them.
        const {singleResource} = await transformAndResolve(sampleConfig());

        const dataSnapshot = singleResource("DataSnapshot");
        expect(dataSnapshot.parameters.otelTraceCollectorEndpoint).toBe("");

        const snapshotMigration = singleResource("SnapshotMigration");
        expect(snapshotMigration.parameters.metadataMigrationOtelTraceCollectorEndpoint).toBe("");
        expect(snapshotMigration.parameters.metadataMigrationTransformerConfig).toBe("");
        expect(snapshotMigration.parameters.metadataMigrationTransformerConfigFile).toBe("");
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
                consoleClientSecretName: "proxy-client-cert",
            },
        };

        const workflowConfig = await new MigrationConfigTransformer().processFromObject(config);
        const transformedProxyConfig = workflowConfig.proxies![0].proxyConfig;
        expect(transformedProxyConfig.tls).toEqual(expect.objectContaining({
            clientAuth: expect.objectContaining({
                consoleClientSecretName: "proxy-client-cert",
            }),
        }));
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
        // clientAuth stays inside spec.tls (gated subtree); flat resolved bridge
        // fields and console-only client material are stripped from the custom resource.
        expect(proxyResource?.parameters).toEqual(expect.objectContaining({
            dependsOn: ["source-proxy-topic"],
            tls: {
                mode: "existingSecret",
                secretName: "proxy-tls",
                clientAuth: {
                    trustedClientCaFile: {
                        configMap: "trusted-client-roots",
                        path: "ca.crt",
                    },
                },
            },
        }));
        expect((proxyResource?.parameters.tls as any).clientAuth.consoleClientSecretName).toBeUndefined();
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
            resource.kind === "TrafficReplay" && resource.name === "replay");
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

    it("includes parameter provenance when resolving from user config", async () => {
        const config = sampleConfig();
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(config);
        const resolvedMigrationResources = buildResolvedMigrationResources(
            workflowConfig,
            "workflow-a",
            {includeParameterProvenance: true, sourceConfig: config}
        );

        const proxy = resolvedMigrationResources.resources.find(resource =>
            resource.kind === "CaptureProxy" && resource.name === "source-proxy");
        expect(proxy?.parameterProvenance?.listenPort).toEqual(expect.objectContaining({
            presence: "authored",
            sourcePath: ["traffic", "proxies", "source-proxy", "proxyConfig", "listenPort"],
            value: 9200,
        }));
        expect(proxy?.parameterProvenance?.dependsOn).toEqual(expect.objectContaining({
            presence: "generated",
            value: ["source-proxy-topic"],
        }));

        const topic = resolvedMigrationResources.resources.find(resource =>
            resource.kind === "CapturedTraffic" && resource.name === "source-proxy-topic");
        expect(topic?.displayFields).toEqual(expect.arrayContaining([
            "topicName",
            "partitions",
            "replicas",
        ]));
        expect(topic?.parameterProvenance?.topicName).toEqual(expect.objectContaining({
            presence: "defaulted",
            sourcePath: ["traffic", "proxies", "source-proxy", "kafkaTopic"],
            value: "source-proxy",
            defaultValue: "source-proxy",
        }));
        expect(topic?.parameterProvenance?.kafkaClusterName).toEqual(expect.objectContaining({
            presence: "defaulted",
            sourcePath: ["traffic", "proxies", "source-proxy", "kafka"],
            value: "default",
        }));

        const replay = resolvedMigrationResources.resources.find(resource =>
            resource.kind === "TrafficReplay" && resource.name === "replay");
        expect(replay?.parameterProvenance?.speedupFactor).toEqual(expect.objectContaining({
            presence: "authored",
            sourcePath: ["traffic", "replayers", "replay", "replayerConfig", "speedupFactor"],
            value: 5,
        }));
        expect(replay?.parameterProvenance?.dependsOn).toEqual(expect.objectContaining({
            presence: "generated",
        }));
    });

    it("loosely projects implicit default kafka refs without inventing a missing explicit cluster", async () => {
        const config = sampleConfig();
        (config as any).traffic.kafkaClusters = {
            kafka: {autoCreate: {}},
        };

        const resolved = await buildLooseResolvedMigrationResources(config, "workflow-a");
        const kafkaClusters = resolved.resources.filter(resource => resource.kind === "KafkaCluster");
        const topic = resolved.resources.find(resource =>
            resource.kind === "CapturedTraffic" && resource.name === "source-proxy-topic");

        expect(resolved.projectionComplete).toBe(false);
        expect(kafkaClusters.map(resource => resource.name)).toEqual(["kafka"]);
        expect(topic?.parameters.kafkaClusterName).toBe("default");
        expect(topic?.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                path: ["traffic", "proxies", "source-proxy", "kafka"],
                message: expect.stringContaining("unknown kafka cluster 'default'"),
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
                name: "replay",
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
            name: "replay",
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
            resource.kind === "TrafficReplay" && resource.name === "replay");
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

    it("loosely projects valid user config with virtual resource provenance", async () => {
        const resolved = await buildLooseResolvedMigrationResources(sampleConfig(), "workflow-a");

        expect(resolved.projectionMode).toBe("loose");
        expect(resolved.projectionComplete).toBe(true);
        expect(resolved.resources.find(resource =>
            resource.kind === "CapturedTraffic" && resource.name === "source-proxy-topic"
        )?.parameterProvenance?.topicName).toEqual(expect.objectContaining({
            presence: "defaulted",
            value: "source-proxy",
        }));
        expect(resolved.consoleResources?.sources).toContainEqual(expect.objectContaining({
            refName: "source",
            parameterProvenance: expect.objectContaining({
                endpoint: expect.objectContaining({
                    presence: "authored",
                    sourcePath: ["sourceClusters", "source", "endpoint"],
                    value: "https://source.example.com",
                }),
                no_auth: expect.objectContaining({
                    presence: "defaulted",
                }),
            }),
        }));
    });

    it("loosely projects config-only snapshot migrations for the manage resource view", async () => {
        const config = sampleConfig();
        ((config.sourceClusters.source.snapshotInfo as any).snapshots as any) = {};
        delete (config.snapshotMigrationConfigs![0] as any).perSnapshotConfig;

        const resolved = await buildLooseResolvedMigrationResources(config, "workflow-a");

        expect(resolved.resources).toContainEqual(expect.objectContaining({
            kind: "SnapshotMigration",
            name: "snapshot migration: source -> target",
            parameters: {
                fromSource: "source",
                toTarget: "target",
            },
            parameterProvenance: expect.objectContaining({
                fromSource: expect.objectContaining({
                    presence: "authored",
                    sourcePath: ["snapshotMigrationConfigs", "0", "fromSource"],
                }),
                toTarget: expect.objectContaining({
                    presence: "authored",
                    sourcePath: ["snapshotMigrationConfigs", "0", "toTarget"],
                }),
            }),
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
        expect(resolved.resources.find((resource: any) =>
            resource.kind === "CapturedTraffic" && resource.name === "source-proxy-topic"
        ).parameterProvenance.topicName).toEqual(expect.objectContaining({
            presence: "defaulted",
            value: "source-proxy",
        }));
    });
});
