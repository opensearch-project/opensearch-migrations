import { MigrationConfigTransformer, normalizeUserConfig } from '../src/migrationConfigTransformer';
import { OVERALL_MIGRATION_CONFIG } from '@opensearch-migrations/schemas';
import { crdName } from '../src/crdNaming';

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
                        "default": { "awsRegion": "us-east-2",
                            "endpoint": "http://localhost:4566",
                            "repoPathUri": "s3://test-bucket" }
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
                    "fromCapturedTraffic": "proxy1",
                    "toTarget": "target1"
                }
            }
        },
        kafkaClusterConfiguration: {
            "default": { "autoCreate": {} }
        }
    };

    const cloneBaseConfig = () => JSON.parse(JSON.stringify(baseConfig));

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

    it('should honor per-proxy skipApproval without global skipApprovals', async () => {
        const config = cloneBaseConfig();
        config.skipApprovals = false;
        config.traffic.proxies.proxy1.skipApproval = true;

        const result = await transformer.processFromObject(config);

        expect(result.proxies?.[0]?.skipApproval).toBe(true);
    });

    it('should let per-proxy skipApproval false override global skipApprovals true', async () => {
        const config = cloneBaseConfig();
        config.skipApprovals = true;
        config.traffic.proxies.proxy1.skipApproval = false;

        const result = await transformer.processFromObject(config);

        expect(result.proxies?.[0]?.skipApproval).toBe(false);
    });

    it('should inherit global skipApprovals when per-proxy skipApproval is omitted', async () => {
        const config = cloneBaseConfig();
        config.skipApprovals = true;
        delete config.traffic.proxies.proxy1.skipApproval;

        const result = await transformer.processFromObject(config);

        expect(result.proxies?.[0]?.skipApproval).toBe(true);
    });

    it('should resolve global skipApprovals into snapshot migration gate flags', async () => {
        const config = cloneBaseConfig();
        config.skipApprovals = true;
        delete config.snapshotMigrationConfigs[0].skipApprovals;
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0] = {
            metadataMigrationConfig: {},
            documentBackfillConfig: {}
        };

        const result = await transformer.processFromObject(config);
        const migration = result.snapshotMigrations?.[0] as any;

        expect(migration?.metadataMigrationConfig?.skipEvaluateApproval).toBe(true);
        expect(migration?.metadataMigrationConfig?.skipMigrateApproval).toBe(true);
        expect(migration?.documentBackfillConfig?.skipApproval).toBe(true);
    });

    it('should let per-migration skipApprovals override global skipApprovals for snapshot gates', async () => {
        const config = cloneBaseConfig();
        config.skipApprovals = true;
        config.snapshotMigrationConfigs[0].skipApprovals = false;
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0] = {
            metadataMigrationConfig: {},
            documentBackfillConfig: {}
        };

        const result = await transformer.processFromObject(config);
        const migration = result.snapshotMigrations?.[0] as any;

        expect(migration?.metadataMigrationConfig?.skipEvaluateApproval).toBe(false);
        expect(migration?.metadataMigrationConfig?.skipMigrateApproval).toBe(false);
        expect(migration?.documentBackfillConfig?.skipApproval).toBe(false);
    });

    it('should let per-gate skip flags override broader skipApprovals', async () => {
        const config = cloneBaseConfig();
        config.skipApprovals = true;
        delete config.snapshotMigrationConfigs[0].skipApprovals;
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0] = {
            metadataMigrationConfig: {
                skipEvaluateApproval: false
            },
            documentBackfillConfig: {
                skipApproval: false
            }
        };

        const result = await transformer.processFromObject(config);
        const migration = result.snapshotMigrations?.[0] as any;

        expect(migration?.metadataMigrationConfig?.skipEvaluateApproval).toBe(false);
        expect(migration?.metadataMigrationConfig?.skipMigrateApproval).toBe(true);
        expect(migration?.documentBackfillConfig?.skipApproval).toBe(false);
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

    it('should reject solrCollections on a user-facing ES/OS createSnapshotConfig', () => {
        // solrCollections is a Solr-only, internal (ARGO) field. It must not be settable on the
        // user-facing ES/OS snapshot config; ES/OS users use indexAllowlist instead.
        const configWithSolrCollections = cloneBaseConfig();
        configWithSolrCollections.sourceClusters.source1.snapshotInfo.snapshots.snap1.config.createSnapshotConfig = {
            solrCollections: ["collectionA"]
        };

        expect(() => {
            transformer.validateInput(configWithSolrCollections);
        }).toThrow(/Unrecognized keys.*solrCollections/);
    });

    it('stamps a sanitized resourceName on each snapshot migration', async () => {
        const result = await transformer.processFromObject(baseConfig);
        const m = result.snapshotMigrations[0];
        // The resolved CRD name is computed once here so downstream consumers
        // (initializer CR name, uid-map key, workflow resourceName) all match.
        expect(m.resourceName).toBe(crdName(m.sourceLabel, m.targetConfig.label, m.label, m.migrationLabel));
        expect(m.resourceName).toBe('source1-target1-snap1-migration-0');
    });

    it('stamps resourceName on each dependsOnSnapshotMigrations entry', async () => {
        const config = cloneBaseConfig();
        config.traffic.replayers.replay1.dependsOnSnapshotMigrations = [
            { source: 'source1', snapshot: 'snap1' }
        ];
        const result = await transformer.processFromObject(config);
        const dep = result.trafficReplays[0].dependsOnSnapshotMigrations[0];
        expect(dep.resourceName).toBe('source1-target1-snap1-migration-0');
    });

    it('should reject s3 traffic sources that reference an unknown kafka cluster', () => {
        const config = cloneBaseConfig();
        config.traffic.s3Sources = {
            "loaded-dump": {
                s3Uri: "s3://traffic-bucket/captures/one.proto.gz",
                awsRegion: "us-east-1",
                kafka: "missing",
                sourceLabel: "detached-source"
            }
        };
        config.traffic.replayers.replay1.fromCapturedTraffic = "loaded-dump";

        expect(() => transformer.validateInput(config))
            .toThrow(/s3Source 'loaded-dump' references unknown kafka cluster 'missing'/);
    });

    it('should transform s3 captured traffic sources without a live proxy', async () => {
        const config = cloneBaseConfig();
        delete config.kafkaClusterConfiguration;
        config.snapshotMigrationConfigs = [];
        config.traffic = {
            s3Sources: {
                "loaded-dump": {
                    s3Uri: "s3://traffic-bucket/captures/one.proto.gz",
                    awsRegion: "us-east-1",
                    endpoint: "http://localstack:4566",
                    sourceLabel: "detached-source"
                }
            },
            replayers: {
                "replay1": {
                    fromCapturedTraffic: "loaded-dump",
                    toTarget: "target1",
                    replayerConfig: {
                        speedupFactor: 2
                    }
                }
            }
        };

        const result = await transformer.processFromObject(config);

        expect(result.proxies).toEqual([]);
        expect(result.kafkaClusters).toEqual([
            expect.objectContaining({
                name: "default",
                topics: ["loaded-dump"]
            })
        ]);

        expect(result.s3TrafficLoaders).toHaveLength(1);
        const loader = result.s3TrafficLoaders![0];
        expect(loader).toEqual(expect.objectContaining({
            name: "loaded-dump",
            sourceLabel: "detached-source",
            s3Uri: "s3://traffic-bucket/captures/one.proto.gz",
            awsRegion: "us-east-1",
            endpoint: "http://localstack:4566",
            kafkaClusterName: "default",
            checksumForReplayer: expect.stringMatching(/^[a-f0-9]{16}$/),
            configChecksum: expect.stringMatching(/^[a-f0-9]{16}$/)
        }));
        expect(loader.kafkaConfig).toEqual(expect.objectContaining({
            label: "default",
            kafkaTopic: "loaded-dump",
            managedByWorkflow: true,
            configChecksum: expect.stringMatching(/^[a-f0-9]{16}$/)
        }));

        expect(result.trafficReplays).toHaveLength(1);
        expect(result.trafficReplays![0]).toEqual(expect.objectContaining({
            name: "loaded-dump-target1-replay1",
            sourceLabel: "detached-source",
            fromCapturedTraffic: "loaded-dump",
            kafkaClusterName: "default",
            dependsOn: ["loaded-dump"],
            fromCapturedTrafficConfigChecksum: loader.checksumForReplayer,
            replayerConfig: expect.objectContaining({
                speedupFactor: 2
            })
        }));
        expect(result.trafficReplays![0].kafkaConfig).toEqual(expect.objectContaining({
            kafkaTopic: "loaded-dump",
            managedByWorkflow: true
        }));
    });

    it('should lower transform pipelines into provider configs with file-source mounts', async () => {
        const config = cloneBaseConfig();
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1 = [
            {
                metadataMigrationConfig: {
                    metadataTransforms: {
                        entryPoint: {
                            javascriptFile: {
                                image: "example.com/transforms@sha256:abc123",
                                path: "metadata.js"
                            }
                        },
                        context: {
                            values: {
                                scope: {value: {phase: "metadata"}}
                            }
                        }
                    }
                },
                documentBackfillConfig: {
                    documentTransforms: [
                        {
                            transformName: "TypeMappingSanitizationTransformerProvider",
                            context: {
                                valueDirectories: [
                                    {configMap: "type-mappings"}
                                ],
                                values: {
                                    staticMappings: {
                                        fromFile: {
                                            configMap: "type-mappings",
                                            path: "staticMappings.json"
                                        }
                                    },
                                    sourceProperties: {
                                        value: {version: "ES 7.10"}
                                    }
                                }
                            }
                        }
                    ]
                }
            }
        ];
        config.traffic.replayers.replay1.replayerConfig = {
            requestTransforms: {
                entryPoint: {
                    javascript: "function transformJson(value) { return value; }"
                },
                context: "request-context"
            },
            tupleTransforms: [
                {
                    entryPoint: {
                        pythonFile: {
                            image: "example.com/transforms@sha256:abc123",
                            path: "tuple.py"
                        }
                    },
                    context: {
                        values: {
                            format: {value: "tuple"}
                        }
                    }
                }
            ]
        };

        const result = await transformer.processFromObject(config);

        const snapshotMigration = result.snapshotMigrations[0];
        expect(snapshotMigration.metadataMigrationConfig!.fileSourceVolumes).toEqual([
            {
                name: expect.stringMatching(/^file-source-[a-f0-9]{12}$/),
                image: {
                    reference: "example.com/transforms@sha256:abc123",
                    pullPolicy: "IfNotPresent"
                }
            }
        ]);
        const metadataMountPath = snapshotMigration.metadataMigrationConfig!.fileSourceVolumeMounts![0].mountPath;
        expect(JSON.parse(snapshotMigration.metadataMigrationConfig!.transformerConfig!)).toEqual([
            {
                JsonJSTransformerProvider: {
                    initializationScriptFile: `${metadataMountPath}/metadata.js`,
                    bindingsObject: {scope: {phase: "metadata"}}
                }
            }
        ]);
        expect(snapshotMigration.documentBackfillConfig!.fileSourceVolumes).toEqual([
            {
                name: expect.stringMatching(/^file-source-[a-f0-9]{12}$/),
                configMap: {name: "type-mappings"}
            }
        ]);
        const documentMountPath = snapshotMigration.documentBackfillConfig!.fileSourceVolumeMounts![0].mountPath;
        expect(JSON.parse(snapshotMigration.documentBackfillConfig!.docTransformerConfig!)).toEqual([
            {
                TypeMappingSanitizationTransformerProvider: {
                    providerConfigDirs: [
                        {path: documentMountPath}
                    ],
                    providerConfigFiles: {
                        staticMappings: {
                            path: `${documentMountPath}/staticMappings.json`
                        }
                    },
                    sourceProperties: {version: "ES 7.10"}
                }
            }
        ]);

        const replayerConfig = result.trafficReplays[0].replayerConfig;
        expect(replayerConfig.fileSourceVolumes).toEqual([
            {
                name: expect.stringMatching(/^file-source-[a-f0-9]{12}$/),
                image: {
                    reference: "example.com/transforms@sha256:abc123",
                    pullPolicy: "IfNotPresent"
                }
            }
        ]);
        const replayerMountPath = replayerConfig.fileSourceVolumeMounts![0].mountPath;
        expect(JSON.parse(replayerConfig.transformerConfig!)).toEqual([
            {
                JsonJSTransformerProvider: {
                    initializationScript: "function transformJson(value) { return value; }",
                    bindingsObject: JSON.stringify("request-context")
                }
            }
        ]);
        expect(JSON.parse(replayerConfig.tupleTransformerConfig!)).toEqual([
            {
                JsonPythonTransformerProvider: {
                    initializationScriptFile: `${replayerMountPath}/tuple.py`,
                    bindingsObject: {format: "tuple"}
                }
            }
        ]);
    });

    it('should preserve image pull policy and dedupe repeated file sources', async () => {
        const config = cloneBaseConfig();
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1 = [
            {
                metadataMigrationConfig: {
                    metadataTransforms: {
                        entryPoint: {
                            javascriptFile: {
                                image: "example.com/transforms:latest",
                                pullPolicy: "Always",
                                path: "metadata.js"
                            }
                        },
                        context: {
                            values: {
                                settings: {
                                    fromFile: {
                                        image: "example.com/transforms:latest",
                                        pullPolicy: "Always",
                                        path: "settings.json"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ];

        const result = await transformer.processFromObject(config);

        const metadataConfig = result.snapshotMigrations[0].metadataMigrationConfig!;
        expect(metadataConfig.fileSourceVolumes).toEqual([
            {
                name: expect.stringMatching(/^file-source-[a-f0-9]{12}$/),
                image: {
                    reference: "example.com/transforms:latest",
                    pullPolicy: "Always"
                }
            }
        ]);
        expect(metadataConfig.fileSourceVolumeMounts).toHaveLength(1);
    });

    it('should lower every supported transform context form and dedupe shared image sources', async () => {
        const config = cloneBaseConfig();
        const transformImage = "example.com/transforms@sha256:abc123";
        config.traffic.replayers.replay1.replayerConfig = {
            requestTransforms: [
                {
                    entryPoint: {
                        javascriptFile: {
                            image: transformImage,
                            path: "request.js"
                        }
                    },
                    context: {
                        valueDirectories: [
                            {
                                image: transformImage,
                                pullPolicy: "IfNotPresent",
                                path: "context/request"
                            }
                        ],
                        values: {
                            headerName: {value: "x-contextual-transform"},
                            nested: {value: {enabled: true}},
                            headerValue: {
                                fromFile: {
                                    image: transformImage,
                                    path: "context/request/headerValue"
                                }
                            }
                        }
                    }
                },
                {
                    entryPoint: {
                        javascript: "function transformJson(value) { return value; }"
                    },
                    context: "plain-string-context"
                },
                {
                    transformName: "TypeMappingSanitizationTransformerProvider",
                    context: {
                        valueDirectories: [
                            {
                                image: transformImage,
                                path: "context/type-mappings"
                            }
                        ],
                        values: {
                            staticMappings: {
                                fromFile: {
                                    image: transformImage,
                                    path: "context/type-mappings/staticMappings.json"
                                }
                            },
                            sourceProperties: {value: {version: "ES 7.10"}}
                        }
                    }
                }
            ],
            tupleTransforms: [
                {
                    entryPoint: {
                        pythonFile: {
                            image: transformImage,
                            path: "tuple.py"
                        }
                    },
                    context: {
                        values: {
                            tupleMode: {value: "contextual"}
                        }
                    }
                },
                {
                    transformName: "TypeMappingSanitizationTransformerProvider",
                    context: "tuple-string-context"
                }
            ]
        };

        const result = await transformer.processFromObject(config);

        const replayerConfig = result.trafficReplays[0].replayerConfig;
        expect(replayerConfig.fileSourceVolumes).toEqual([
            {
                name: expect.stringMatching(/^file-source-[a-f0-9]{12}$/),
                image: {
                    reference: transformImage,
                    pullPolicy: "IfNotPresent"
                }
            }
        ]);
        expect(replayerConfig.fileSourceVolumeMounts).toHaveLength(1);
        const mountPath = replayerConfig.fileSourceVolumeMounts![0].mountPath;

        expect(JSON.parse(replayerConfig.transformerConfig!)).toEqual([
            {
                JsonJSTransformerProvider: {
                    initializationScriptFile: `${mountPath}/request.js`,
                    bindingsObjectDirs: [
                        {path: `${mountPath}/context/request`}
                    ],
                    bindingsObjectFiles: {
                        headerValue: {
                            path: `${mountPath}/context/request/headerValue`
                        }
                    },
                    bindingsObject: {
                        headerName: "x-contextual-transform",
                        nested: {enabled: true}
                    }
                }
            },
            {
                JsonJSTransformerProvider: {
                    initializationScript: "function transformJson(value) { return value; }",
                    bindingsObject: JSON.stringify("plain-string-context")
                }
            },
            {
                TypeMappingSanitizationTransformerProvider: {
                    providerConfigDirs: [
                        {path: `${mountPath}/context/type-mappings`}
                    ],
                    providerConfigFiles: {
                        staticMappings: {
                            path: `${mountPath}/context/type-mappings/staticMappings.json`
                        }
                    },
                    sourceProperties: {version: "ES 7.10"}
                }
            }
        ]);
        expect(JSON.parse(replayerConfig.tupleTransformerConfig!)).toEqual([
            {
                JsonPythonTransformerProvider: {
                    initializationScriptFile: `${mountPath}/tuple.py`,
                    bindingsObject: {tupleMode: "contextual"}
                }
            },
            {
                TypeMappingSanitizationTransformerProvider: "tuple-string-context"
            }
        ]);
    });

    it('should reject transform specs without exactly one selector', () => {
        const config = cloneBaseConfig();
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1 = [
            {
                metadataMigrationConfig: {
                    metadataTransforms: {
                        entryPoint: {
                            javascript: "function transformJson(value) { return value; }"
                        },
                        transformName: "TypeMappingSanitizationTransformerProvider"
                    }
                }
            }
        ];

        expect(() => transformer.validateInput(config))
            .toThrow(/Exactly one of entryPoint or transformName is required/);
    });

    it('should reject legacy transform source fields', () => {
        const config = cloneBaseConfig();
        config.transformsSources = {};

        expect(() => transformer.validateInput(config))
            .toThrow(/Unrecognized keys at root: transformsSources/);
    });

    it('should lower capture proxy client-auth trust material into file-source mounts', async () => {
        const config = cloneBaseConfig();
        config.traffic.proxies.proxy1.proxyConfig.tls = {
            mode: "existingSecret",
            secretName: "proxy-tls",
            clientAuth: {
                trustedClientCaFile: {
                    configMap: "trusted-client-roots",
                    path: "ca.crt"
                }
            }
        };

        const result = await transformer.processFromObject(config);
        const proxyConfig = result.proxies[0].proxyConfig;
        const mountPath = proxyConfig.fileSourceVolumeMounts![0].mountPath;

        // clientAuth is retained in tls (rides into the gated CR spec.tls); the
        // flat fields below are the Deployment/Java-process projection of it.
        expect(proxyConfig.tls).toEqual({
            mode: "existingSecret",
            secretName: "proxy-tls",
            clientAuth: {
                required: true,
                trustedClientCaFile: {
                    configMap: "trusted-client-roots",
                    path: "ca.crt"
                }
            }
        });
        expect(proxyConfig.sslTrustCertFile).toBe(`${mountPath}/ca.crt`);
        expect(proxyConfig.requireClientAuth).toBe(true);
        expect(proxyConfig.fileSourceVolumes).toEqual([
            {
                name: expect.stringMatching(/^file-source-[a-f0-9]{12}$/),
                configMap: {name: "trusted-client-roots"}
            }
        ]);
    });

    it('should lower inline capture proxy client-auth trust material without mounts', async () => {
        const config = cloneBaseConfig();
        const pem = [
            "-----BEGIN CERTIFICATE-----",
            "MIIBtest",
            "-----END CERTIFICATE-----"
        ].join("\n");
        config.traffic.proxies.proxy1.proxyConfig.tls = {
            mode: "existingSecret",
            secretName: "proxy-tls",
            clientAuth: {
                trustedClientCaPem: pem,
                required: false
            }
        };

        const result = await transformer.processFromObject(config);
        const proxyConfig = result.proxies[0].proxyConfig;

        expect(proxyConfig.tls).toEqual({
            mode: "existingSecret",
            secretName: "proxy-tls",
            clientAuth: {
                trustedClientCaPem: pem,
                required: false
            }
        });
        expect(proxyConfig.sslTrustCertPem).toBe(pem);
        expect(proxyConfig.sslTrustCertPemEnvVar).toBe("CAPTURE_PROXY_SSL_TRUST_CERT_PEM");
        expect(proxyConfig.sslTrustCertFile).toBeUndefined();
        expect(proxyConfig.requireClientAuth).toBe(false);
        expect(proxyConfig.fileSourceVolumes).toEqual([]);
        expect(proxyConfig.fileSourceVolumeMounts).toEqual([]);
    });

    it('should keep workload identity stable across gated RFS changes only', async () => {
        const withBackfill = JSON.parse(JSON.stringify(baseConfig));
        withBackfill.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig = {
            maxConnections: 4,
        };

        const gatedChange = JSON.parse(JSON.stringify(withBackfill));
        gatedChange.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig.maxConnections = 5;

        const impossibleChange = JSON.parse(JSON.stringify(withBackfill));
        impossibleChange.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig.indexAllowlist = [
            "logs-*",
        ];

        const baselineMigration = (await transformer.processFromObject(withBackfill)).snapshotMigrations[0];
        const gatedMigration = (await transformer.processFromObject(gatedChange)).snapshotMigrations[0];
        const impossibleMigration = (await transformer.processFromObject(impossibleChange)).snapshotMigrations[0];

        expect(gatedMigration.configChecksum).not.toEqual(baselineMigration.configChecksum);
        expect(gatedMigration.workloadIdentityChecksum).toEqual(baselineMigration.workloadIdentityChecksum);
        expect(impossibleMigration.workloadIdentityChecksum).not.toEqual(baselineMigration.workloadIdentityChecksum);
    });

    it('should include source connection changes in snapshot and migration identity', async () => {
        const sourceEndpointChange = JSON.parse(JSON.stringify(baseConfig));
        sourceEndpointChange.sourceClusters.source1.endpoint = "https://alternate-source:9200";

        const baseline = await transformer.processFromObject(baseConfig);
        const changed = await transformer.processFromObject(sourceEndpointChange);

        const baselineSnapshot = baseline.snapshots[0].createSnapshotConfig[0];
        const changedSnapshot = changed.snapshots[0].createSnapshotConfig[0];
        const baselineMigration = baseline.snapshotMigrations[0];
        const changedMigration = changed.snapshotMigrations[0];

        expect(changedSnapshot.configChecksum).not.toEqual(baselineSnapshot.configChecksum);
        expect(changedMigration.sourceEndpoint).toBe("https://alternate-source:9200");
        expect(changedMigration.snapshotConfigChecksum).not.toEqual(baselineMigration.snapshotConfigChecksum);
        expect(changedMigration.configChecksum).not.toEqual(baselineMigration.configChecksum);
        expect(changedMigration.workloadIdentityChecksum).not.toEqual(baselineMigration.workloadIdentityChecksum);
    });

    it('should include target connection changes in snapshot migration workload identity', async () => {
        const targetEndpointChange = JSON.parse(JSON.stringify(baseConfig));
        targetEndpointChange.targetClusters.target1.endpoint = "https://alternate-target:9200";

        const baselineMigration = (await transformer.processFromObject(baseConfig)).snapshotMigrations[0];
        const changedMigration = (await transformer.processFromObject(targetEndpointChange)).snapshotMigrations[0];

        expect(changedMigration.targetConfig.label).toBe(baselineMigration.targetConfig.label);
        expect(changedMigration.configChecksum).not.toEqual(baselineMigration.configChecksum);
        expect(changedMigration.workloadIdentityChecksum).not.toEqual(baselineMigration.workloadIdentityChecksum);
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

    it('should preserve the expert proxy Service type setting', async () => {
        const configWithClusterIpProxy = cloneBaseConfig();
        configWithClusterIpProxy.traffic.proxies.proxy1.proxyConfig.serviceType = "ClusterIP";

        const result = await transformer.processFromObject(configWithClusterIpProxy);

        expect(result.proxies?.[0]?.proxyConfig).toMatchObject({
            listenPort: 9201,
            serviceType: "ClusterIP",
        });
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
                            topologySpreadConstraints: [
                                {
                                    maxSkew: 1,
                                    topologyKey: "kubernetes.io/hostname",
                                    whenUnsatisfiable: "ScheduleAnyway",
                                    labelSelector: {
                                        matchExpressions: [
                                            {
                                                key: "strimzi.io/name",
                                                operator: "Exists",
                                            },
                                        ],
                                    },
                                },
                            ],
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
                        fromCapturedTraffic: "proxy1",
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
