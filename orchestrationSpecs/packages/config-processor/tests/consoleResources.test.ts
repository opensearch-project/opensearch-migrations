import {describe, expect, it} from "@jest/globals";
import {promises as fs} from "fs";
import * as os from "os";
import * as path from "path";
import {
    buildConsoleResources,
    buildConsoleResourcesFromResolvedConfig,
    buildResolvedMigrationResources,
    MigrationConfigTransformer,
} from "../src";
import {main as resolveConsoleResourcesMain} from "../src/resolveConsoleResources";

function multiResourceConfig() {
    return {
        kafkaClusterConfiguration: {
            default: {
                autoCreate: {},
            },
            "my-kafka": {
                autoCreate: {
                    auth: {
                        type: "none",
                    },
                },
            },
        },
        sourceClusters: {
            sourceA: {
                endpoint: "https://source-a.example.com",
                allowInsecure: true,
                version: "ES 7.10.2",
                authConfig: {
                    basic: {
                        secretName: "source-a-creds",
                    },
                },
                snapshotInfo: {
                    repos: {
                        repoA: {
                            awsRegion: "us-east-2",
                            s3RepoPathUri: "s3://bucket-a",
                        },
                    },
                    snapshots: {
                        snapA: {
                            repoName: "repoA",
                            config: {
                                createSnapshotConfig: {},
                            },
                        },
                    },
                },
            },
            sourceB: {
                endpoint: "https://source-b.example.com",
                allowInsecure: false,
                version: "OS 1.3.0",
                authConfig: {
                    sigv4: {
                        region: "us-west-2",
                        service: "es",
                    },
                },
            },
        },
        targetClusters: {
            targetX: {
                endpoint: "https://target-x.example.com",
                allowInsecure: true,
                authConfig: {
                    basic: {
                        secretName: "target-x-creds",
                    },
                },
            },
            targetY: {
                endpoint: "https://target-y.example.com",
                allowInsecure: false,
            },
        },
        snapshotMigrationConfigs: [{
            fromSource: "sourceA",
            toTarget: "targetX",
            perSnapshotConfig: {
                snapA: [{
                    metadataMigrationConfig: {},
                }],
            },
        }],
        traffic: {
            proxies: {
                "proxy-a": {
                    source: "sourceA",
                    proxyConfig: {
                        listenPort: 9201,
                    },
                },
                "proxy-b": {
                    source: "sourceB",
                    kafka: "my-kafka",
                    proxyConfig: {
                        listenPort: 9202,
                        tls: {
                            mode: "plaintext",
                        },
                    },
                },
            },
            replayers: {
                replayA: {
                    fromCapturedTraffic: "proxy-a",
                    toTarget: "targetX",
                },
                replayB: {
                    fromCapturedTraffic: "proxy-b",
                    toTarget: "targetY",
                },
            },
        },
    } as any;
}

describe("console resources", () => {
    it("projects source, target, proxy, kafka, and consumer-group resources", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(multiResourceConfig());
        const resources = buildConsoleResources(workflowConfig, "workflow-a");

        expect(resources.sources).toEqual([
            expect.objectContaining({
                refName: "sourceA",
                aliases: ["sourceA"],
                clientConfig: expect.objectContaining({
                    endpoint: "https://source-a.example.com",
                    allow_insecure: true,
                    basic_auth: {k8s_secret_name: "source-a-creds"},
                }),
                proxy: expect.objectContaining({
                    refName: "proxy-a",
                    k8sName: "proxy-a",
                    aliases: expect.arrayContaining([
                        "proxy-a",
                        "captureproxy.proxy-a",
                    ]),
                    clientConfig: expect.objectContaining({
                        endpoint: "https://proxy-a:9201",
                        allow_insecure: true,
                        basic_auth: {k8s_secret_name: "source-a-creds"},
                    }),
                }),
            }),
            expect.objectContaining({
                refName: "sourceB",
                clientConfig: expect.objectContaining({
                    endpoint: "https://source-b.example.com",
                    sigv4: {
                        region: "us-west-2",
                        service: "es",
                    },
                }),
                proxy: expect.objectContaining({
                    clientConfig: expect.objectContaining({
                        endpoint: "http://proxy-b:9202",
                        allow_insecure: false,
                        sigv4_signing_endpoint: "https://source-b.example.com",
                    }),
                }),
            }),
        ]);

        expect(resources.targets).toEqual([
            expect.objectContaining({
                refName: "targetX",
                clientConfig: expect.objectContaining({
                    endpoint: "https://target-x.example.com",
                    basic_auth: {k8s_secret_name: "target-x-creds"},
                }),
            }),
            expect.objectContaining({
                refName: "targetY",
                clientConfig: expect.objectContaining({
                    endpoint: "https://target-y.example.com",
                    no_auth: null,
                }),
            }),
        ]);

        expect(resources.kafkas).toEqual([
            expect.objectContaining({
                refName: "default",
                k8sName: "default",
                aliases: expect.arrayContaining([
                    "default",
                    "kafkacluster.default",
                ]),
                runtime: expect.objectContaining({
                    type: "strimzi",
                    clusterName: "default",
                    authType: "scram-sha-512",
                    listenerName: "tls",
                    usernameSecret: "default-migration-app",
                    caSecret: "default-cluster-ca-cert",
                }),
            }),
            expect.objectContaining({
                refName: "my-kafka",
                k8sName: "my-kafka",
                aliases: expect.arrayContaining([
                    "my-kafka",
                    "kafkacluster.my-kafka",
                ]),
                runtime: expect.objectContaining({
                    type: "strimzi",
                    clusterName: "my-kafka",
                    authType: "none",
                    listenerName: "plain",
                }),
            }),
        ]);

        expect(resources.consumerGroups).toEqual([
            {
                name: "replayer-targetX",
                targetRef: "targetX",
                kafkaRef: "default",
                replayRef: "proxy-a-targetX-replayA",
            },
            {
                name: "replayer-targetY",
                targetRef: "targetY",
                kafkaRef: "my-kafka",
                replayRef: "proxy-b-targetY-replayB",
            },
        ]);
        expect(resources.sources[0].proxy?.aliases).not.toContain(
            "captureproxies.migrations.opensearch.org/proxy-a"
        );
        expect(resources.kafkas[0].aliases).not.toContain(
            "kafkaclusters.migrations.opensearch.org/default"
        );
    });

    it("projects console resources from resolved migration resources", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(multiResourceConfig());
        const resolvedConfig = buildResolvedMigrationResources(workflowConfig, "workflow-a");

        const resources = buildConsoleResourcesFromResolvedConfig(resolvedConfig);

        expect(resources.workflowName).toBe("workflow-a");
        expect(resources.sources[0].source).toBe("migrationRun");
        expect(resources.targets[0].source).toBe("migrationRun");
        expect(resources.kafkas[0].source).toBe("migrationRun");
    });

    it("projects externally managed SCRAM Kafka credential metadata", async () => {
        const config = multiResourceConfig();
        config.kafkaClusterConfiguration = {
            external: {
                existing: {
                    kafkaConnection: "broker.example.com:9093",
                    auth: {
                        type: "scram-sha-512",
                        secretName: "external-kafka-user",
                        caSecretName: "external-kafka-ca",
                        kafkaUserName: "migration-app",
                    },
                },
            },
        };
        config.traffic.proxies = {
            "proxy-a": {
                source: "sourceA",
                kafka: "external",
                proxyConfig: {
                    listenPort: 9201,
                },
            },
        };
        config.traffic.replayers = {
            replayA: {
                fromCapturedTraffic: "proxy-a",
                toTarget: "targetX",
            },
        };

        const workflowConfig = await new MigrationConfigTransformer().processFromObject(config);
        const resources = buildConsoleResources(workflowConfig);

        expect(resources.kafkas).toEqual([
            expect.objectContaining({
                refName: "external",
                runtime: expect.objectContaining({
                    type: "direct",
                    secretName: "external-kafka-user",
                    caSecretName: "external-kafka-ca",
                    kafkaUserName: "migration-app",
                    clientConfig: {
                        broker_endpoints: "broker.example.com:9093",
                        scram: {
                            username: "migration-app",
                        },
                    },
                }),
            }),
        ]);
    });

    it("writes console resources from the command line", async () => {
        const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "console-resources-cli-test-"));
        const inputFile = path.join(outputDir, "workflow.yaml");
        const outputFile = path.join(outputDir, "consoleResources.json");
        await fs.writeFile(inputFile, JSON.stringify(multiResourceConfig(), null, 2));

        await resolveConsoleResourcesMain([
            "--user-config", inputFile,
            "--workflow-name", "workflow-from-cli",
            "--output", outputFile,
        ]);

        const resources = JSON.parse(await fs.readFile(outputFile, "utf8"));
        expect(resources.workflowName).toBe("workflow-from-cli");
        expect(resources.sources.map((source: any) => source.refName)).toEqual(["sourceA", "sourceB"]);
        expect(resources.kafkas.map((kafka: any) => kafka.refName)).toEqual(["default", "my-kafka"]);
    });
});
