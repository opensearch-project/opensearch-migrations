"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var globals_1 = require("@jest/globals");
var findSecrets_1 = require("../src/findSecrets");
(0, globals_1.describe)('scrapeSecrets', function () {
    (0, globals_1.it)('should extract secret names from source clusters with basic auth', function () {
        var config = {
            skipApprovals: false,
            sourceClusters: {
                source1: {
                    authConfig: {
                        basic: {
                            secretName: 'source-secret-1'
                        }
                    }
                }
            },
            targetClusters: {},
        };
        var secrets = (0, findSecrets_1.scrapeSecrets)(config);
        (0, globals_1.expect)(secrets).toEqual(['source-secret-1']);
    });
    (0, globals_1.it)('should extract secret names from target clusters with basic auth', function () {
        var config = {
            targetClusters: {
                target1: {
                    authConfig: {
                        basic: {
                            secretName: 'target-secret-1'
                        }
                    }
                }
            }
        };
        var secrets = (0, findSecrets_1.scrapeSecrets)(config);
        (0, globals_1.expect)(secrets).toEqual(['target-secret-1']);
    });
    (0, globals_1.it)('should extract secret names from both source and target clusters', function () {
        var config = {
            sourceClusters: {
                source1: {
                    authConfig: {
                        basic: {
                            secretName: 'source-secret-1'
                        }
                    }
                },
                source2: {
                    version: 'ES 6.8.0',
                    authConfig: {
                        basic: {
                            secretName: 'source-secret-2'
                        }
                    }
                }
            },
            targetClusters: {
                target1: {
                    authConfig: {
                        basic: {
                            secretName: 'target-secret-1'
                        }
                    }
                }
            }
        };
        var secrets = (0, findSecrets_1.scrapeSecrets)(config);
        (0, globals_1.expect)(secrets).toHaveLength(3);
        (0, globals_1.expect)(secrets).toContain('source-secret-1');
        (0, globals_1.expect)(secrets).toContain('source-secret-2');
        (0, globals_1.expect)(secrets).toContain('target-secret-1');
    });
    (0, globals_1.it)('should ignore clusters without auth config', function () {
        var config = {
            skipApprovals: false,
            sourceClusters: {
                source1: {
                    version: 'ES 7.10.2'
                }
            },
            targetClusters: {
                target1: {
                    endpoint: 'https://target.example.com:9200'
                }
            },
            migrationConfigs: []
        };
        var secrets = (0, findSecrets_1.scrapeSecrets)(config);
        (0, globals_1.expect)(secrets).toEqual([]);
    });
    (0, globals_1.it)('should return empty array for empty clusters', function () {
        var config = {
            skipApprovals: false,
            sourceClusters: {},
            targetClusters: {},
            migrationConfigs: []
        };
        var secrets = (0, findSecrets_1.scrapeSecrets)(config);
        (0, globals_1.expect)(secrets).toEqual([]);
    });
});
(0, globals_1.describe)('scrapeAndCategorize', function () {
    (0, globals_1.it)('should categorize valid K8s secret names', function () {
        var config = {
            sourceClusters: {
                source1: {
                    authConfig: {
                        basic: {
                            secretName: 'valid-secret-name'
                        }
                    }
                },
                source2: {
                    version: 'ES -1.invalidVersion',
                    authConfig: {
                        basic: {
                            secretName: 'another-valid-123'
                        }
                    }
                }
            }
        };
        var result = (0, findSecrets_1.getCategorizedCredentialsSecretsFromConfig)(config);
        (0, globals_1.expect)(result.validSecrets).toHaveLength(2);
        (0, globals_1.expect)(result.validSecrets).toContain('valid-secret-name');
        (0, globals_1.expect)(result.validSecrets).toContain('another-valid-123');
        (0, globals_1.expect)(result.invalidSecrets).toBeUndefined();
    });
    (0, globals_1.it)('should categorize invalid K8s secret names', function () {
        var config = {
            sourceClusters: {
                source1: {
                    authConfig: {
                        basic: {
                            secretName: 'Invalid_Secret_Name'
                        }
                    }
                },
                source2: {
                    version: 'ES 6.8.0',
                    authConfig: {
                        basic: {
                            secretName: '-invalid-start'
                        }
                    }
                },
                source3: {
                    version: 'ES 5.6.0',
                    authConfig: {
                        basic: {
                            secretName: 'UPPERCASE'
                        }
                    }
                }
            },
            targetClusters: {},
            migrationConfigs: []
        };
        var result = (0, findSecrets_1.getCategorizedCredentialsSecretsFromConfig)(config);
        (0, globals_1.expect)(result.invalidSecrets).toHaveLength(3);
        (0, globals_1.expect)(result.invalidSecrets).toContain('Invalid_Secret_Name');
        (0, globals_1.expect)(result.invalidSecrets).toContain('-invalid-start');
        (0, globals_1.expect)(result.invalidSecrets).toContain('UPPERCASE');
        (0, globals_1.expect)(result.validSecrets).toBeUndefined();
    });
    (0, globals_1.it)('should categorize mixed valid and invalid secret names', function () {
        var config = {
            skipApprovals: false,
            sourceClusters: {
                source1: {
                    version: 'ES 7.10.2',
                    authConfig: {
                        basic: {
                            secretName: 'valid-secret'
                        }
                    }
                },
                source2: {
                    version: 'ES 6.8.0',
                    authConfig: {
                        basic: {
                            secretName: 'Invalid_Secret'
                        }
                    }
                }
            },
            targetClusters: {
                target1: {
                    endpoint: 'https://target.example.com:9200',
                    authConfig: {
                        basic: {
                            secretName: 'another-valid.secret'
                        }
                    }
                },
                target2: {
                    endpoint: 'https://target2.example.com:9200',
                    authConfig: {
                        basic: {
                            secretName: '.bad.secret.name'
                        }
                    }
                }
            },
            migrationConfigs: []
        };
        var result = (0, findSecrets_1.getCategorizedCredentialsSecretsFromConfig)(config);
        (0, globals_1.expect)(result.validSecrets).toHaveLength(2);
        (0, globals_1.expect)(result.validSecrets).toContain('valid-secret');
        (0, globals_1.expect)(result.validSecrets).toContain('another-valid.secret');
        (0, globals_1.expect)(result.invalidSecrets).toHaveLength(2);
        (0, globals_1.expect)(result.invalidSecrets).toContain('Invalid_Secret');
        (0, globals_1.expect)(result.invalidSecrets).toContain('.bad.secret.name');
    });
    (0, globals_1.it)('should handle empty result when no secrets found', function () {
        var config = {};
        var result = (0, findSecrets_1.getCategorizedCredentialsSecretsFromConfig)(config);
        (0, globals_1.expect)(result.validSecrets).toBeUndefined();
        (0, globals_1.expect)(result.invalidSecrets).toBeUndefined();
    });
});
