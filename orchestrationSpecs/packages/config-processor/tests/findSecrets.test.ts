import { describe, it, expect } from '@jest/globals';
import { scrapeSecrets, getCategorizedCredentialsSecretsFromConfig } from '../src/findSecrets';
import { z } from 'zod';
import { OVERALL_MIGRATION_CONFIG } from '@opensearch-migrations/schemas';

describe('scrapeSecrets', () => {
    it('should extract secret names from source clusters with basic auth', () => {
        const config = {
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

        const secrets = scrapeSecrets(config as any);
        expect(secrets).toEqual(['source-secret-1']);
    });

    it('should extract secret names from target clusters with basic auth', () => {
        const config = {
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

        const secrets = scrapeSecrets(config as any);
        expect(secrets).toEqual(['target-secret-1']);
    });

    it('should extract secret names from both source and target clusters', () => {
        const config = {
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

        const secrets = scrapeSecrets(config as any);
        expect(secrets).toHaveLength(3);
        expect(secrets).toContain('source-secret-1');
        expect(secrets).toContain('source-secret-2');
        expect(secrets).toContain('target-secret-1');
    });

    it('should ignore clusters without auth config', () => {
        const config: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
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

        const secrets = scrapeSecrets(config as any);
        expect(secrets).toEqual([]);
    });

    it('should return empty array for empty clusters', () => {
        const config: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            skipApprovals: false,
            sourceClusters: {},
            targetClusters: {},
            migrationConfigs: []
        };

        const secrets = scrapeSecrets(config as any);
        expect(secrets).toEqual([]);
    });
});

describe('scrapeAndCategorize', () => {
    it('should categorize valid K8s secret names', () => {
        const config = {
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

        const result = getCategorizedCredentialsSecretsFromConfig(config as any);
        expect(result.validSecrets).toHaveLength(2);
        expect(result.validSecrets).toContain('valid-secret-name');
        expect(result.validSecrets).toContain('another-valid-123');
        expect(result.invalidSecrets).toBeUndefined();
    });

    it('should categorize invalid K8s secret names', () => {
        const config = {
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

        const result = getCategorizedCredentialsSecretsFromConfig(config as any);
        expect(result.invalidSecrets).toHaveLength(3);
        expect(result.invalidSecrets).toContain('Invalid_Secret_Name');
        expect(result.invalidSecrets).toContain('-invalid-start');
        expect(result.invalidSecrets).toContain('UPPERCASE');
        expect(result.validSecrets).toBeUndefined();
    });

    it('should categorize mixed valid and invalid secret names', () => {
        const config = {
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

        const result = getCategorizedCredentialsSecretsFromConfig(config as any);
        expect(result.validSecrets).toHaveLength(2);
        expect(result.validSecrets).toContain('valid-secret');
        expect(result.validSecrets).toContain('another-valid.secret');
        expect(result.invalidSecrets).toHaveLength(2);
        expect(result.invalidSecrets).toContain('Invalid_Secret');
        expect(result.invalidSecrets).toContain('.bad.secret.name');
    });

    it('should handle empty result when no secrets found', () => {
        const config = {};

        const result = getCategorizedCredentialsSecretsFromConfig(config);
        expect(result.validSecrets).toBeUndefined();
        expect(result.invalidSecrets).toBeUndefined();
    });
});
