import { describe, it, expect } from '@jest/globals';
import { MigrationConfigTransformer, MigrationInitializer } from "../src";

describe('semaphore configuration', () => {
    const transformer = new MigrationConfigTransformer();

    it('generates shared semaphore key for legacy versions (multiple snapshots)', async () => {
        const config = {
            sourceClusters: {
                legacy_source: {
                    endpoint: "https://legacy.example.com",
                    allowInsecure: true,
                    version: "ES 7.10.2",
                    authConfig: {
                        basic: {
                            secretName: "legacy-creds"
                        }
                    },
                    snapshotRepos: {
                        default: {
                            awsRegion: "us-east-2",
                            s3RepoPathUri: "s3://bucket/path"
                        }
                    },
                    proxy: {}
                }
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com",
                    allowInsecure: true,
                    authConfig: {
                        basic: {
                            secretName: "target-creds"
                        }
                    }
                }
            },
            migrationConfigs: [
                {
                    fromSource: "legacy_source",
                    toTarget: "target",
                    snapshotExtractAndLoadConfigs: [
                        {
                            snapshotConfig: {
                                repoName: "default",
                                snapshotNameConfig: {
                                    snapshotNamePrefix: "snap1"
                                }
                            },
                            createSnapshotConfig: {},
                            migrations: [
                                {
                                    metadataMigrationConfig: {
                                        skipEvaluateApproval: true,
                                        skipMigrateApproval: true
                                    }
                                }
                            ]
                        },
                        {
                            snapshotConfig: {
                                repoName: "default", 
                                snapshotNameConfig: {
                                    snapshotNamePrefix: "snap2"
                                }
                            },
                            createSnapshotConfig: {},
                            migrations: [
                                {
                                    metadataMigrationConfig: {
                                        skipEvaluateApproval: true,
                                        skipMigrateApproval: true
                                    }
                                }
                            ]
                        }
                    ]
                }
            ]
        };

        const result = await transformer.processFromObject(config);
        const semaphoreKeys = result[0].snapshotExtractAndLoadConfigArray!.map(
            config => config.createSnapshotConfig.semaphoreKey
        );
        const uniqueKeys = new Set(semaphoreKeys);
        
        expect(uniqueKeys.size).toBe(1); // Legacy: shared semaphore
    });

    it('generates unique semaphore keys for modern versions (multiple snapshots)', async () => {
        const config = {
            sourceClusters: {
                modern_source: {
                    endpoint: "https://modern.example.com",
                    allowInsecure: true,
                    version: "OS 2.5.0",
                    authConfig: {
                        basic: {
                            secretName: "modern-creds"
                        }
                    },
                    snapshotRepos: {
                        default: {
                            awsRegion: "us-east-2",
                            s3RepoPathUri: "s3://bucket/path"
                        }
                    },
                    proxy: {}
                }
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com",
                    allowInsecure: true,
                    authConfig: {
                        basic: {
                            secretName: "target-creds"
                        }
                    }
                }
            },
            migrationConfigs: [
                {
                    fromSource: "modern_source",
                    toTarget: "target",
                    snapshotExtractAndLoadConfigs: [
                        {
                            snapshotConfig: {
                                repoName: "default",
                                snapshotNameConfig: {
                                    snapshotNamePrefix: "snap1"
                                }
                            },
                            createSnapshotConfig: {},
                            migrations: [
                                {
                                    metadataMigrationConfig: {
                                        skipEvaluateApproval: true,
                                        skipMigrateApproval: true
                                    }
                                }
                            ]
                        },
                        {
                            snapshotConfig: {
                                repoName: "default",
                                snapshotNameConfig: {
                                    snapshotNamePrefix: "snap2"
                                }
                            },
                            createSnapshotConfig: {},
                            migrations: [
                                {
                                    metadataMigrationConfig: {
                                        skipEvaluateApproval: true,
                                        skipMigrateApproval: true
                                    }
                                }
                            ]
                        }
                    ]
                }
            ]
        };

        const result = await transformer.processFromObject(config);
        const semaphoreKeys = result[0].snapshotExtractAndLoadConfigArray!.map(
            config => config.createSnapshotConfig.semaphoreKey
        );
        const uniqueKeys = new Set(semaphoreKeys);
        
        expect(uniqueKeys.size).toBe(2); // Modern: unique semaphores
    });

    it('generates correct ConfigMap YAML with semaphore keys', async () => {
        const config = {
            sourceClusters: {
                legacy_source: {
                    endpoint: "https://legacy.example.com",
                    allowInsecure: true,
                    version: "ES 7.10.2",
                    authConfig: {
                        basic: {
                            secretName: "legacy-creds"
                        }
                    },
                    snapshotRepos: {
                        default: {
                            awsRegion: "us-east-2",
                            s3RepoPathUri: "s3://bucket/path"
                        }
                    },
                    proxy: {}
                },
                modern_source: {
                    endpoint: "https://modern.example.com",
                    allowInsecure: true,
                    version: "OS 2.5.0",
                    authConfig: {
                        basic: {
                            secretName: "modern-creds"
                        }
                    },
                    snapshotRepos: {
                        default: {
                            awsRegion: "us-east-2",
                            s3RepoPathUri: "s3://bucket/path"
                        }
                    },
                    proxy: {}
                }
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com",
                    allowInsecure: true,
                    authConfig: {
                        basic: {
                            secretName: "target-creds"
                        }
                    }
                }
            },
            migrationConfigs: [
                {
                    fromSource: "legacy_source",
                    toTarget: "target",
                    snapshotExtractAndLoadConfigs: [
                        {
                            snapshotConfig: {
                                repoName: "default",
                                snapshotNameConfig: {
                                    snapshotNamePrefix: "snap1"
                                }
                            },
                            createSnapshotConfig: {},
                            migrations: [
                                {
                                    metadataMigrationConfig: {
                                        skipEvaluateApproval: true,
                                        skipMigrateApproval: true
                                    }
                                }
                            ]
                        }
                    ]
                },
                {
                    fromSource: "modern_source",
                    toTarget: "target",
                    snapshotExtractAndLoadConfigs: [
                        {
                            snapshotConfig: {
                                repoName: "default",
                                snapshotNameConfig: {
                                    snapshotNamePrefix: "snap2"
                                }
                            },
                            createSnapshotConfig: {},
                            migrations: [
                                {
                                    metadataMigrationConfig: {
                                        skipEvaluateApproval: true,
                                        skipMigrateApproval: true
                                    }
                                }
                            ]
                        }
                    ]
                }
            ]
        };

        const initializer = new MigrationInitializer({endpoints: ["localhost"]}, 'test-nonce');
        const concurrencyConfigMaps = (initializer as any).generateConcurrencyConfigMaps(config);
        
        const concurrencyConfigMap = concurrencyConfigMaps.items[0];
        expect(concurrencyConfigMap.metadata.name).toBe('concurrency-config');
        
        // Should have exactly 2 semaphore keys, each set to '1'
        const semaphoreData = concurrencyConfigMap.data;
        expect(Object.keys(semaphoreData)).toHaveLength(2);
        Object.values(semaphoreData).forEach(value => {
            expect(value).toBe('1');
        });

        // Test that initializer produces consistent semaphore keys between workflow and ConfigMap
        const bundle = await initializer.generateMigrationBundle(config);
        
        // Extract semaphore keys from transformed workflow
        const workflowSemaphoreKeys = new Set();
        bundle.workflows.forEach(migration => {
            migration.snapshotExtractAndLoadConfigArray?.forEach(snapConfig => {
                workflowSemaphoreKeys.add(snapConfig.createSnapshotConfig.semaphoreKey);
            });
        });
        
        // Extract semaphore keys from ConfigMap
        const configMapKeys = new Set(Object.keys(bundle.concurrencyConfigMaps.items[0].data));
        expect(workflowSemaphoreKeys).toEqual(configMapKeys);
    });
});
