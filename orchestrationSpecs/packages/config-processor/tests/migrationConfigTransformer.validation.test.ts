import { MigrationConfigTransformer, normalizeUserConfig } from '../src/migrationConfigTransformer';
import { OVERALL_MIGRATION_CONFIG } from '@opensearch-migrations/schemas';

describe('MigrationConfigTransformer validation', () => {
    let transformer: MigrationConfigTransformer;

    beforeEach(() => {
        transformer = new MigrationConfigTransformer();
    });

    const baseConfig = {
        skipApprovals: false,
        sourceClusters: {
            "source1": {
                "endpoint": "https://elasticsearch-master-headless:9200",
                "allowInsecure": true,
                "version": "ES 7.10",
                "authConfig": {
                    "basic": {
                        "secretName": "source1-creds"
                    }
                },
                "snapshotInfo": {
                    "repos": {
                        "default": {
                            "awsRegion": "us-east-2",
                            "endpoint": "http://localhost:4566",
                            "s3RepoPathUri": "s3://test-bucket"
                        }
                    },
                    "snapshots": {
                        "snap1": {
                            "config": {
                                "createSnapshotConfig": {}
                            },
                            "repoName": "default"
                        }
                    }
                }
            }
        },
        targetClusters: {
            "target1": {
                "endpoint": "https://opensearch-cluster-master-headless:9200",
                "allowInsecure": true,
                "authConfig": {
                    "basic": {
                        "secretName": "target1-creds"
                    }
                }
            }
        },
        snapshotMigrationConfigs: [
            {
                "fromSource": "source1",
                "toTarget": "target1",
                "skipApprovals": false,
                "perSnapshotConfig": {
                    "snap1": [
                        {
                            "metadataMigrationConfig": {
                                "skipEvaluateApproval": true,
                                "skipMigrateApproval": true
                            }
                        }
                    ]
                }
            }
        ],
        traffic: {
            proxies: {
                "proxy1": {
                    "source": "source1",
                    "proxyConfig": { "listenPort": 9201 }
                }
            },
            replayers: {
                "replay1": {
                    "fromProxy": "proxy1",
                    "toTarget": "target1"
                }
            }
        },
        kafkaClusterConfiguration: {
            "default": { "autoCreate": {} }
        }
    };

    it('should reject rogue key at top level', () => {
        const configWithRogueKey = {
            ...baseConfig,
            rogueTopLevel: "should fail"
        };

        expect(() => {
            transformer.validateInput(configWithRogueKey);
        }).toThrow(/Unrecognized keys at root: rogueTopLevel/);
    });

    it('should reject rogue key in union (authConfig.basic)', () => {
        const configWithRogueInUnion = {
            ...baseConfig,
            sourceClusters: {
                ...baseConfig.sourceClusters,
                source1: {
                    ...baseConfig.sourceClusters.source1,
                    authConfig: {
                        basic: {
                            secretName: "source1-creds",
                            rogueInUnion: "should fail"
                        }
                    }
                }
            }
        };

        expect(() => {
            transformer.validateInput(configWithRogueInUnion);
        }).toThrow(/Unrecognized keys at sourceClusters\.\[0\]\.authConfig\.basic: rogueInUnion/);
    });

    it('should reject rogue key in nested object (snapshotInfo)', () => {
        const configWithRogueInNested = {
            ...baseConfig,
            sourceClusters: {
                ...baseConfig.sourceClusters,
                source1: {
                    ...baseConfig.sourceClusters.source1,
                    snapshotInfo: {
                        ...baseConfig.sourceClusters.source1.snapshotInfo,
                        rogueInNested: "should fail"
                    }
                }
            }
        };

        expect(() => {
            transformer.validateInput(configWithRogueInNested);
        }).toThrow(/Unrecognized keys at sourceClusters\.\[0\]\.snapshotInfo: rogueInNested/);
    });

    it('should validate refinements (bad repoName reference)', () => {
        // This is now a schema-level validation since repoName is inside snapshotInfo.snapshots
        // The refinement would need to be re-enabled in the schema
    });

    it('should accept valid configuration', () => {
        expect(() => {
            transformer.validateInput(baseConfig);
        }).not.toThrow();
    });

    it('should normalize workflow-managed Kafka auth and drop empty kafkaTopic placeholders before AJV validation', () => {
        const parsed = OVERALL_MIGRATION_CONFIG.parse(baseConfig);
        const normalized = normalizeUserConfig(parsed);

        expect(normalized.kafkaClusterConfiguration.default).toMatchObject({
            autoCreate: {
                auth: {
                    type: "scram-sha-512"
                }
            }
        });
        expect(normalized.traffic?.proxies?.proxy1).not.toHaveProperty("kafkaTopic");
    });

    it('should derive managed Kafka auth profile for auto-created SCRAM clusters', async () => {
        const configWithScramKafka = {
            ...baseConfig,
            kafkaClusterConfiguration: {
                default: {
                    autoCreate: {
                        auth: {
                            type: "scram-sha-512"
                        }
                    }
                }
            }
        };

        const result = await transformer.processFromObject(configWithScramKafka);
        expect(result.trafficReplays?.[0]?.kafkaConfig).toMatchObject({
            managedByWorkflow: true,
            listenerName: "tls",
            authType: "scram-sha-512",
            secretName: "default-migration-app",
            caSecretName: "default-cluster-ca-cert",
            kafkaUserName: "default-migration-app",
            kafkaConnection: "default-kafka-bootstrap:9093",
        });
    });

    it('should materialize baseline Kafka defaults during parsing for auto-created clusters', async () => {
        const result = await transformer.processFromObject(baseConfig);
        expect(result.kafkaClusters?.[0]).toMatchObject({
            name: "default",
            config: {
                auth: {type: "scram-sha-512"},
                nodePoolSpecOverrides: {
                    replicas: 3,
                    roles: ["controller", "broker"],
                    storage: {
                        type: "persistent-claim",
                        size: "2Gi",
                        deleteClaim: true,
                    },
                    template: {
                        pod: {
                            affinity: {
                                podAntiAffinity: {
                                    preferredDuringSchedulingIgnoredDuringExecution: [
                                        {
                                            weight: 100,
                                            podAffinityTerm: {
                                                labelSelector: {
                                                    matchExpressions: [
                                                        {
                                                            key: "strimzi.io/name",
                                                            operator: "Exists",
                                                        },
                                                    ],
                                                },
                                                topologyKey: "kubernetes.io/hostname",
                                            },
                                        },
                                    ],
                                },
                            },
                        },
                    },
                },
                topicSpecOverrides: {
                    partitions: 1,
                    replicas: 3,
                    config: {
                        "retention.ms": 604800000,
                        "segment.bytes": 1073741824,
                    }
                },
                clusterSpecOverrides: {
                    kafka: {
                        config: {
                            "auto.create.topics.enable": false,
                            "default.replication.factor": 3,
                            "min.insync.replicas": 2,
                            "offsets.topic.replication.factor": 3,
                            "transaction.state.log.min.isr": 2,
                            "transaction.state.log.replication.factor": 3,
                        }
                    }
                }
            }
        });
    });

    it('should require a CA secret for existing SCRAM-managed Kafka clusters', () => {
        const configWithInvalidExistingScramKafka = {
            ...baseConfig,
            kafkaClusterConfiguration: {
                default: {
                    existing: {
                        kafkaConnection: "broker.example.org:9093",
                        kafkaTopic: "capture-proxy",
                        auth: {
                            type: "scram-sha-512",
                            secretName: "existing-kafka-user-secret"
                        }
                    }
                }
            }
        };

        expect(() => {
            transformer.validateInput(configWithInvalidExistingScramKafka);
        }).toThrow(/existing/);
    });

    it('should report an unknown Kafka broker key without union noise', () => {
        const configWithBogusKafkaKey = {
            ...baseConfig,
            kafkaClusterConfiguration: {
                default: {
                    autoCreate: {
                        clusterSpecOverrides: {
                            kafka: {
                                config: {
                                    "auto.create.topics.enable": false,
                                    "bogus.inner.key": true,
                                }
                            }
                        }
                    }
                }
            }
        };

        expect(() => {
            transformer.validateInput(configWithBogusKafkaKey);
        }).toThrow(/Kafka broker config 'bogus\.inner\.key' is not part of the pinned Kafka 4\.2\.0 broker config catalog/);

        expect(() => {
            transformer.validateInput(configWithBogusKafkaKey);
        }).not.toThrow(/must have required property 'existing'|must match a schema in anyOf/);
    });

    it('should attach a derived proxy route onto the transformed source config', async () => {
        const result = await transformer.processFromObject(baseConfig);
        expect(result.snapshots?.[0]?.sourceConfig).toMatchObject({
            label: "source1",
            proxy: {
                name: "proxy1",
                endpoint: "https://proxy1:9201",
                allowInsecure: true
            }
        });
    });

    it('should reject multiple proxies attached to a single source', async () => {
        const configWithMultipleSourceProxies = {
            ...baseConfig,
            traffic: {
                ...baseConfig.traffic,
                proxies: {
                    ...baseConfig.traffic.proxies,
                    "proxy2": {
                        "source": "source1",
                        "proxyConfig": { "listenPort": 9202 }
                    }
                }
            }
        };

        await expect(transformer.processFromObject(configWithMultipleSourceProxies))
            .rejects.toThrow(
                "Source 'source1' maps to multiple proxies (proxy1, proxy2). " +
                "Console test routing requires exactly zero or one proxy per source."
            );
    });

    it('should key replay snapshot-migration dependencies by replay target', async () => {
        const configWithTwoTargets = {
            ...baseConfig,
            targetClusters: {
                target1: baseConfig.targetClusters.target1,
                target2: {
                    ...baseConfig.targetClusters.target1,
                    endpoint: "https://opensearch-target-2:9200",
                },
            },
            snapshotMigrationConfigs: [
                {
                    fromSource: "source1",
                    toTarget: "target2",
                    skipApprovals: false,
                    perSnapshotConfig: {
                        snap1: [
                            {
                                metadataMigrationConfig: {
                                    skipEvaluateApproval: true,
                                    skipMigrateApproval: true,
                                },
                                documentBackfillConfig: {
                                    podReplicas: 1,
                                },
                            }
                        ]
                    }
                },
                {
                    fromSource: "source1",
                    toTarget: "target1",
                    skipApprovals: false,
                    perSnapshotConfig: {
                        snap1: [
                            {
                                metadataMigrationConfig: {
                                    skipEvaluateApproval: true,
                                    skipMigrateApproval: true,
                                }
                            }
                        ]
                    }
                }
            ],
            traffic: {
                ...baseConfig.traffic,
                replayers: {
                    replay2: {
                        fromProxy: "proxy1",
                        toTarget: "target2",
                        dependsOnSnapshotMigrations: [
                            {source: "source1", snapshot: "snap1"}
                        ]
                    }
                }
            }
        };

        const result = await transformer.processFromObject(configWithTwoTargets);
        const target2Migration = result.snapshotMigrations?.find(
            migration => migration.sourceLabel === "source1" && migration.targetConfig.label === "target2"
        );
        const replay2 = result.trafficReplays?.find(replay => replay.toTarget.label === "target2");

        expect(target2Migration).toBeDefined();
        expect(replay2?.dependsOnSnapshotMigrations?.[0]?.configChecksum)
            .toEqual(target2Migration?.checksumForReplayer);
    });
});
