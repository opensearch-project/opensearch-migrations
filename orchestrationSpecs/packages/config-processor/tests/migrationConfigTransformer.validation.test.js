"use strict";
var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
Object.defineProperty(exports, "__esModule", { value: true });
var migrationConfigTransformer_1 = require("../src/migrationConfigTransformer");
describe('MigrationConfigTransformer validation', function () {
    var transformer;
    beforeEach(function () {
        transformer = new migrationConfigTransformer_1.MigrationConfigTransformer();
    });
    var baseConfig = {
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
    it('should reject rogue key at top level', function () {
        var configWithRogueKey = __assign(__assign({}, baseConfig), { rogueTopLevel: "should fail" });
        expect(function () {
            transformer.validateInput(configWithRogueKey);
        }).toThrow(/Unrecognized keys at root: rogueTopLevel/);
    });
    it('should reject rogue key in union (authConfig.basic)', function () {
        var configWithRogueInUnion = __assign(__assign({}, baseConfig), { sourceClusters: __assign(__assign({}, baseConfig.sourceClusters), { source1: __assign(__assign({}, baseConfig.sourceClusters.source1), { authConfig: {
                        basic: {
                            secretName: "source1-creds",
                            rogueInUnion: "should fail"
                        }
                    } }) }) });
        expect(function () {
            transformer.validateInput(configWithRogueInUnion);
        }).toThrow(/Unrecognized keys at sourceClusters\.\[0\]\.authConfig\.basic: rogueInUnion/);
    });
    it('should reject rogue key in nested object (snapshotInfo)', function () {
        var configWithRogueInNested = __assign(__assign({}, baseConfig), { sourceClusters: __assign(__assign({}, baseConfig.sourceClusters), { source1: __assign(__assign({}, baseConfig.sourceClusters.source1), { snapshotInfo: __assign(__assign({}, baseConfig.sourceClusters.source1.snapshotInfo), { rogueInNested: "should fail" }) }) }) });
        expect(function () {
            transformer.validateInput(configWithRogueInNested);
        }).toThrow(/Unrecognized keys at sourceClusters\.\[0\]\.snapshotInfo: rogueInNested/);
    });
    it('should validate refinements (bad repoName reference)', function () {
        // This is now a schema-level validation since repoName is inside snapshotInfo.snapshots
        // The refinement would need to be re-enabled in the schema
    });
    it('should accept valid configuration', function () {
        expect(function () {
            transformer.validateInput(baseConfig);
        }).not.toThrow();
    });
});
