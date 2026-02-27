"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
Object.defineProperty(exports, "__esModule", { value: true });
var globals_1 = require("@jest/globals");
var src_1 = require("../src");
(0, globals_1.describe)('semaphore configuration', function () {
    var transformer = new src_1.MigrationConfigTransformer();
    (0, globals_1.it)('generates shared semaphore key for legacy versions (multiple snapshots)', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, result, semaphoreKeys, uniqueKeys;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = {
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
                    return [4 /*yield*/, transformer.processFromObject(config)];
                case 1:
                    result = _a.sent();
                    semaphoreKeys = result[0].snapshotExtractAndLoadConfigArray.map(function (config) { return config.createSnapshotConfig.semaphoreKey; });
                    uniqueKeys = new Set(semaphoreKeys);
                    (0, globals_1.expect)(uniqueKeys.size).toBe(1); // Legacy: shared semaphore
                    return [2 /*return*/];
            }
        });
    }); });
    (0, globals_1.it)('generates unique semaphore keys for modern versions (multiple snapshots)', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, result, semaphoreKeys, uniqueKeys;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = {
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
                    return [4 /*yield*/, transformer.processFromObject(config)];
                case 1:
                    result = _a.sent();
                    semaphoreKeys = result[0].snapshotExtractAndLoadConfigArray.map(function (config) { return config.createSnapshotConfig.semaphoreKey; });
                    uniqueKeys = new Set(semaphoreKeys);
                    (0, globals_1.expect)(uniqueKeys.size).toBe(2); // Modern: unique semaphores
                    return [2 /*return*/];
            }
        });
    }); });
    (0, globals_1.it)('generates correct ConfigMap YAML with semaphore keys', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, initializer, concurrencyConfigMaps, concurrencyConfigMap, semaphoreData, bundle, workflowSemaphoreKeys, configMapKeys;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = {
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
                    initializer = new src_1.MigrationInitializer({ endpoints: ["localhost"] }, 'test-nonce');
                    concurrencyConfigMaps = initializer.generateConcurrencyConfigMaps(config);
                    concurrencyConfigMap = concurrencyConfigMaps.items[0];
                    (0, globals_1.expect)(concurrencyConfigMap.metadata.name).toBe('concurrency-config');
                    semaphoreData = concurrencyConfigMap.data;
                    (0, globals_1.expect)(Object.keys(semaphoreData)).toHaveLength(2);
                    Object.values(semaphoreData).forEach(function (value) {
                        (0, globals_1.expect)(value).toBe('1');
                    });
                    return [4 /*yield*/, initializer.generateMigrationBundle(config)];
                case 1:
                    bundle = _a.sent();
                    workflowSemaphoreKeys = new Set();
                    bundle.workflows.forEach(function (migration) {
                        var _a;
                        (_a = migration.snapshotExtractAndLoadConfigArray) === null || _a === void 0 ? void 0 : _a.forEach(function (snapConfig) {
                            workflowSemaphoreKeys.add(snapConfig.createSnapshotConfig.semaphoreKey);
                        });
                    });
                    configMapKeys = new Set(Object.keys(bundle.concurrencyConfigMaps.items[0].data));
                    (0, globals_1.expect)(workflowSemaphoreKeys).toEqual(configMapKeys);
                    return [2 /*return*/];
            }
        });
    }); });
});
