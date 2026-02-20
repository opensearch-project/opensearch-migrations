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
                            "repoName": "default",
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
    it('should reject rogue key in nested object (snapshotConfig)', function () {
        var configWithRogueInNested = __assign(__assign({}, baseConfig), { migrationConfigs: [
                __assign(__assign({}, baseConfig.migrationConfigs[0]), { snapshotExtractAndLoadConfigs: [
                        __assign(__assign({}, baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0]), { snapshotConfig: __assign(__assign({}, baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0].snapshotConfig), { rogueInNested: "should fail" }) })
                    ] })
            ] });
        expect(function () {
            transformer.validateInput(configWithRogueInNested);
        }).toThrow(/Unrecognized keys at migrationConfigs\.0\.snapshotExtractAndLoadConfigs\.0\.snapshotConfig: rogueInNested/);
    });
    it('should validate refinements (bad repoName reference)', function () {
        var configWithBadRepoName = __assign(__assign({}, baseConfig), { migrationConfigs: [
                __assign(__assign({}, baseConfig.migrationConfigs[0]), { snapshotExtractAndLoadConfigs: [
                        __assign(__assign({}, baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0]), { snapshotConfig: __assign(__assign({}, baseConfig.migrationConfigs[0].snapshotExtractAndLoadConfigs[0].snapshotConfig), { repoName: "nonexistent" }) })
                    ] })
            ] });
        expect(function () {
            transformer.validateInput(configWithBadRepoName);
        }).toThrow(/repoName 'nonexistent' does not exist in source cluster 'source1'|Found \d+ errors/);
    });
    it('should accept valid configuration', function () {
        expect(function () {
            transformer.validateInput(baseConfig);
        }).not.toThrow();
    });
});
