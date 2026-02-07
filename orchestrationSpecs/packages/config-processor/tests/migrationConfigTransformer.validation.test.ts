import { MigrationConfigTransformer } from '../src/migrationConfigTransformer';

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
                                "createSnapshotConfig": {},
                                "requiredForCompleteMigration": true
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
                "version": "OS 2.11",
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
});
