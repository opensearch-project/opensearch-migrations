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
                "snapshotRepos": {
                    "default": {
                        "awsRegion": "us-east-2",
                        "endpoint": "http://localhost:4566",
                        "s3RepoPathUri": "s3://test-bucket"
                    }
                },
                "proxy": {}
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
        migrationConfigs: [
            {
                "fromSource": "source1",
                "toTarget": "target1",
                "skipApprovals": false,
                "snapshotExtractAndLoadConfigs": [
                    {
                        "snapshotConfig": {
                            "snapshotNameConfig": {
                                "snapshotNamePrefix": "test-snapshot"
                            }
                        },
                        "createSnapshotConfig": {},
                        "migrations": [
                            {
                                "metadataMigrationConfig": {
                                    "skipEvaluateApproval": true,
                                    "skipMigrateApproval": true
                                }
                            }
                        ]
                    }
                ]
            }
        ]
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

    it('should reject rogue key in nested object (snapshotConfig)', () => {
        const configWithRogueInNested = {
            ...baseConfig,
            migrationConfigs: [
                {
                    ...baseConfig.migrationConfigs[0],
                    snapshotExtractAndLoadConfigs: [
                        {
                            ...baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0],
                            snapshotConfig: {
                                ...baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0].snapshotConfig,
                                rogueInNested: "should fail"
                            }
                        }
                    ]
                }
            ]
        };

        expect(() => {
            transformer.validateInput(configWithRogueInNested);
        }).toThrow(/Unrecognized keys at migrationConfigs\.0\.snapshotExtractAndLoadConfigs\.0\.snapshotConfig: rogueInNested/);
    });

    it('should validate refinements (bad repoName reference)', () => {
        const configWithBadRepoName = {
            ...baseConfig,
            migrationConfigs: [
                {
                    ...baseConfig.migrationConfigs[0],
                    snapshotExtractAndLoadConfigs: [
                        {
                            ...baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0],
                            snapshotConfig: {
                                ...baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0].snapshotConfig,
                                repoName: "nonexistent"
                            }
                        }
                    ]
                }
            ]
        };

        expect(() => {
            transformer.validateInput(configWithBadRepoName);
        }).toThrow(/repoName 'nonexistent' does not exist in source cluster 'source1'|Found \d+ errors/);
    });

    it('should accept valid configuration', () => {
        expect(() => {
            transformer.validateInput(baseConfig);
        }).not.toThrow();
    });
});
