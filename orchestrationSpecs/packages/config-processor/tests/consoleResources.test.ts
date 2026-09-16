import {describe, expect, it, jest} from "@jest/globals";
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

jest.setTimeout(30_000);

function multiResourceConfig() {
    return {
        sourceClusters: {
            sourcea: {
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
                            repoPathUri: "s3://bucket-a",
                            endpoint: "localstack://localstack.ma.svc.cluster.local:4566",
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
            sourceb: {
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
            targetx: {
                endpoint: "https://target-x.example.com",
                allowInsecure: true,
                authConfig: {
                    basic: {
                        secretName: "target-x-creds",
                    },
                },
            },
            targety: {
                endpoint: "https://target-y.example.com",
                allowInsecure: false,
            },
        },
        snapshotMigrationConfigs: [{
            fromSource: "sourcea",
            toTarget: "targetx",
                fromSnapshot: "snapA",
                slices: {
                    "slice-0": {
                    metadataMigrationConfig: {},
                    },
                },
            },],
        traffic: {
            kafkaClusters: {
                default: {
                    autoCreate: {},
                    topics: {
                        "proxy-a": {},
                    },
                },
                "my-kafka": {
                    autoCreate: {
                        auth: {
                            type: "none",
                        },
                    },
                    topics: {
                        "proxy-b": {},
                    },
                },
            },
            proxies: {
                "proxy-a": {
                    source: "sourcea",
                    kafka: "default",
                    kafkaTopic: "proxy-a",
                    proxyConfig: {
                        listenPort: 9201,
                        tls: {
                            mode: "existingSecret",
                            secretName: "proxy-a-tls",
                            clientAuth: {
                                trustedClientCaPem: "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n",
                                consoleClientSecretName: "proxy-a-console-client",
                            },
                        },
                    },
                },
                "proxy-b": {
                    source: "sourceb",
                    kafka: "my-kafka",
                    kafkaTopic: "proxy-b",
                    proxyConfig: {
                        listenPort: 9202,
                        tls: {
                            mode: "plaintext",
                        },
                    },
                },
            },
            replayers: {
                "replay-a": {
                    fromCapturedTraffic: "proxy-a",
                    toTarget: "targetx",
                },
                "replay-b": {
                    fromCapturedTraffic: "proxy-b",
                    toTarget: "targety",
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
                refName: "sourcea",
                aliases: ["sourcea"],
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
                        "captureproxy.proxy-a"
                    ]),
                    clientConfig: expect.objectContaining({
                        endpoint: "https://proxy-a:9201",
                        allow_insecure: true,
                        basic_auth: {k8s_secret_name: "source-a-creds"},
                        client_cert: {k8s_secret_name: "proxy-a-console-client"},
                    }),
                }),
                consumers: expect.arrayContaining([
                    expect.objectContaining({
                        kind: "CaptureProxy",
                        name: "proxy-a",
                        role: "capture source",
                        configChecksum: expect.any(String),
                    }),
                ]),
            }),
            expect.objectContaining({
                refName: "sourceb",
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
                refName: "targetx",
                clientConfig: expect.objectContaining({
                    endpoint: "https://target-x.example.com",
                    basic_auth: {k8s_secret_name: "target-x-creds"},
                }),
                consumers: expect.arrayContaining([
                    expect.objectContaining({
                        kind: "SnapshotMigration",
                        role: "migration target",
                        configChecksum: expect.any(String),
                    }),
                    expect.objectContaining({
                        kind: "TrafficReplay",
                        role: "replay target",
                        configChecksum: expect.any(String),
                    }),
                ]),
            }),
            expect.objectContaining({
                refName: "targety",
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
                    "kafkacluster.default"
                ]),
                runtime: expect.objectContaining({
                    type: "strimzi",
                    clusterName: "default",
                    authType: "scram-sha-512",
                    listenerName: "tls",
                    usernameSecret: "default-migration-app",
                    caSecret: "default-cluster-ca-cert",
                }),
                consumers: expect.arrayContaining([
                    expect.objectContaining({
                        kind: "KafkaCluster",
                        name: "default",
                        role: "managed kafka",
                        configChecksum: expect.any(String),
                    }),
                    expect.objectContaining({
                        kind: "CaptureProxy",
                        name: "proxy-a",
                        role: "capture kafka",
                        configChecksum: expect.any(String),
                    }),
                    expect.objectContaining({
                        kind: "CapturedTraffic",
                        name: "proxy-a-topic",
                        role: "capture topic",
                        configChecksum: expect.any(String),
                    }),
                ]),
            }),
            expect.objectContaining({
                refName: "my-kafka",
                k8sName: "my-kafka",
                aliases: expect.arrayContaining([
                    "my-kafka",
                    "kafkacluster.my-kafka"
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
                name: "replayer-targetx",
                targetRef: "targetx",
                kafkaRef: "default",
                replayRef: "replay-a",
            },
            {
                name: "replayer-targety",
                targetRef: "targety",
                kafkaRef: "my-kafka",
                replayRef: "replay-b",
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

    it("projects historical resolved configs that predate new workflow defaults", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(multiResourceConfig());
        const resolvedConfig = buildResolvedMigrationResources(workflowConfig, "workflow-a") as any;
        delete resolvedConfig.workflowConfig.requireBeginApproval;
        const removeSolrContextPath = (value: unknown): void => {
            if (Array.isArray(value)) {
                value.forEach(removeSolrContextPath);
                return;
            }
            if (typeof value !== "object" || value === null) {
                return;
            }
            for (const [key, child] of Object.entries(value)) {
                if (
                    key.endsWith("ConnectionIdentity")
                    && typeof child === "object"
                    && child !== null
                ) {
                    delete (child as Record<string, unknown>).solrContextPath;
                }
                removeSolrContextPath(child);
            }
        };
        removeSolrContextPath(resolvedConfig.workflowConfig);

        const resources = buildConsoleResourcesFromResolvedConfig(resolvedConfig);

        expect(resources.workflowName).toBe("workflow-a");
        expect(resources.sources.map((source) => source.refName)).toEqual(["sourcea", "sourceb"]);
    });

    it("projects historical resolved configs that fail current nested validation", async () => {
        const workflowConfig = await new MigrationConfigTransformer().processFromObject(multiResourceConfig());
        const resolvedConfig = buildResolvedMigrationResources(workflowConfig, "workflow-a") as any;
        const sourceConfigs = [
            ...resolvedConfig.workflowConfig.proxies.map((proxy: any) => proxy.sourceConfig),
            ...resolvedConfig.workflowConfig.snapshots.map((snapshot: any) => snapshot.sourceConfig),
        ].filter((source: any) => source.label === "sourcea" && source.snapshotInfo);
        for (const sourceConfig of sourceConfigs) {
            sourceConfig.snapshotInfo.repos["r a"] = sourceConfig.snapshotInfo.repos.repoA;
            delete sourceConfig.snapshotInfo.repos.repoA;
            sourceConfig.snapshotInfo.snapshots.snapA.repoName = "r a";
        }

        const resources = buildConsoleResourcesFromResolvedConfig(resolvedConfig);

        expect(resources.workflowName).toBe("workflow-a");
        expect(resources.sources.map((source) => source.refName)).toEqual(["sourcea", "sourceb"]);
        expect(resources.targets.map((target) => target.refName)).toEqual(["targetx", "targety"]);
    });

    it("projects externally managed SCRAM Kafka credential metadata", async () => {
        const config = multiResourceConfig();
        config.traffic.kafkaClusters = {
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
                topics: {
                    "proxy-a": {},
                },
            },
        };
        config.traffic.proxies = {
            "proxy-a": {
                source: "sourcea",
                kafka: "external",
                kafkaTopic: "proxy-a",
                proxyConfig: {
                    listenPort: 9201,
                },
            },
        };
        config.traffic.replayers = {
            "replay-a": {
                fromCapturedTraffic: "proxy-a",
                toTarget: "targetx",
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
        const config = multiResourceConfig();
        config.sourceClusters.unused = {
            endpoint: "https://unused-source.example.com",
            version: "OS 2.19.0",
            snapshotInfo: {
                repos: {
                    emptyRepo: {
                        repoPathUri: "gs://empty-repository",
                    },
                },
                snapshots: {},
            },
        };
        config.targetClusters["unused-target"] = {
            endpoint: "https://unused-target.example.com",
        };
        await fs.writeFile(inputFile, JSON.stringify(config, null, 2));

        await resolveConsoleResourcesMain([
            "--user-config", inputFile,
            "--workflow-name", "workflow-from-cli",
            "--output", outputFile,
        ]);

        const resources = JSON.parse(await fs.readFile(outputFile, "utf8"));
        expect(resources.workflowName).toBe("workflow-from-cli");
        expect(resources.sources.map((source: any) => source.refName)).toEqual(["sourcea", "sourceb", "unused"]);
        expect(resources.targets.map((target: any) => target.refName)).toEqual(
            ["targetx", "targety", "unused-target"],
        );
        expect(resources.kafkas.map((kafka: any) => kafka.refName)).toEqual(["default", "my-kafka"]);
        expect(resources.sources.find((source: any) => source.refName === "sourcea")).toEqual(
            expect.objectContaining({
                editPath: ["sourceClusters", "sourcea"],
                repositories: [{
                    refName: "repoA",
                    provider: "s3",
                    editPath: ["sourceClusters", "sourcea", "snapshotInfo", "repos", "repoA"],
                    clientConfig: {
                        repo_uri: "s3://bucket-a",
                        s3_region: "us-east-2",
                        endpoint: "http://localstack.ma.svc.cluster.local:4566",
                        use_local_stack: true,
                    },
                    snapshots: [{
                        refName: "snapA",
                        generated: true,
                        editPath: ["sourceClusters", "sourcea", "snapshotInfo", "snapshots", "snapA"],
                    }],
                }],
            }),
        );
        expect(resources.sources.find((source: any) => source.refName === "unused")).toEqual({
            refName: "unused",
            aliases: ["unused"],
            clientConfig: {
                endpoint: "https://unused-source.example.com",
                version: "OS 2.19.0",
                allow_insecure: false,
                no_auth: null,
            },
            displayFields: expect.any(Array),
            editPath: ["sourceClusters", "unused"],
            repositories: [{
                refName: "emptyRepo",
                provider: "gcs",
                editPath: ["sourceClusters", "unused", "snapshotInfo", "repos", "emptyRepo"],
                clientConfig: {
                    repo_uri: "gs://empty-repository",
                },
                snapshots: [],
            }],
            source: "config",
        });
    });
});
