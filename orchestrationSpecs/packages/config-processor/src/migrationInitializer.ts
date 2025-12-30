import {StreamSchemaParser} from "./streamSchemaTransformer";
import {
    ARGO_WORKFLOW_SCHEMA, K8S_NAMING_PATTERN,
    PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
} from "@opensearch-migrations/schemas";
import {Etcd3} from "etcd3";
import {z} from "zod";
import {stringify} from "yaml";
import * as fs from "fs/promises";
import * as path from "path";
import {scrapeApprovals} from "./formatApprovals";
import {setNamesInUserConfig} from "./migrationConfigTransformer";

/** etcd connection options */
export interface EtcdOptions {
    endpoints: string[];           // e.g., "http://127.0.0.1:2379" or array
    auth?: {
        username: string;
        password: string;
    }
}

export class MigrationInitializer {
    readonly client: Etcd3;
    readonly loader: StreamSchemaParser<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;
    constructor(etcdSettings: EtcdOptions, public readonly uniqueRunNonce: string) {
        if (!K8S_NAMING_PATTERN.test(uniqueRunNonce)) {
            throw new Error(`Illegal uniqueRunNonce argument.  Must match regex pattern ${K8S_NAMING_PATTERN}.`);
        }
        console.log("Initializing with " + JSON.stringify(etcdSettings));
        this.client = new Etcd3({
            hosts: etcdSettings.endpoints,
            auth: etcdSettings.auth
        });
        this.loader = new StreamSchemaParser(PARAMETERIZED_MIGRATION_CONFIG_ARRAYS);
    }

    private calculateProcessorCount(targetMigrations: z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG>[]): number {
        let count = 0;
        let hasReplayersConfigured = false;
        for (const c of targetMigrations) {
            if (c.replayerConfig !== undefined) {
                hasReplayersConfigured = true;
            }

            for (const snapshots of c.snapshotExtractAndLoadConfigArray??[]) {
                for (const m of snapshots.migrations) {
                    count += 1;
                }
            }
        }

        return hasReplayersConfigured ? count : 0;
    }

    async initializeWorkflow(workflows: ARGO_WORKFLOW_SCHEMA): Promise<void> {
        try {
            // Store workflow metadata
            await this.client.put(`/${this.uniqueRunNonce}/workflow/info/prefix`).value(this.uniqueRunNonce);
            await this.client.put(`/${this.uniqueRunNonce}/workflow/info/started`).value(
                Math.floor(Date.now() / 1000).toString()
            );

            const targetsMap =
                Object.groupBy(workflows, w=> w.targetConfig.name);

            // Initialize target latches
            for (const [targetName, list] of Object.entries(targetsMap)) {
                const processorCount = list ? this.calculateProcessorCount(list) : 0;
                console.log(`Total processor count: ${processorCount}`);

                await this.client.put(`/${this.uniqueRunNonce}/workflow/targets/${targetName}/latch`)
                    .value(processorCount.toString());

                console.log(`Target ${targetName} (${targetName}) latch initialized with count ${processorCount}`);
            }

            console.log(`Etcd keys initialized with prefix: ${this.uniqueRunNonce}`);
        } catch (error) {
            console.error('Error initializing workflow:', error);
            throw error;
        }
    }

    /**
     * Close etcd client connection
     */
    async close(): Promise<void> {
        await this.client.close();
    }

    /**
     * Generate output files including workflow config, approval ConfigMaps, and concurrency ConfigMaps with semaphores
     */
    async generateOutputFiles(workflows: ARGO_WORKFLOW_SCHEMA, outputDir: string, userConfig: any): Promise<void> {
        await fs.mkdir(outputDir, { recursive: true });

        // 1. Write workflow configuration (semaphores already added during transformation)
        const workflowPath = path.join(outputDir, 'workflowMigration.config.yaml');
        await fs.writeFile(workflowPath, JSON.stringify(workflows, null, 2));

        // 2. Write approval config maps
        const approvalConfigMaps = this.generateApprovalConfigMaps(userConfig);
        const approvalPath = path.join(outputDir, 'approvalConfigMaps.yaml');
        await fs.writeFile(approvalPath, stringify(approvalConfigMaps));

        // 3. Write concurrency config maps (includes semaphores)
        const concurrencyConfigMaps = this.generateConcurrencyConfigMaps(userConfig);
        const concurrencyPath = path.join(outputDir, 'concurrencyConfigMaps.yaml');
        await fs.writeFile(concurrencyPath, stringify(concurrencyConfigMaps));
    }

    private generateApprovalConfigMaps(userConfig: any) {
        if (!userConfig) {
            return { 
                apiVersion: 'v1',
                kind: 'List',
                items: [] 
            };
        }

        const approvals = scrapeApprovals(setNamesInUserConfig(userConfig));
        
        return {
            apiVersion: 'v1',
            kind: 'List',
            items: [{
                apiVersion: 'v1',
                kind: 'ConfigMap',
                metadata: {
                    name: 'approval-config',
                    labels: {
                        'workflows.argoproj.io/configmap-type': 'Parameter'
                    }
                },
                data: {
                    'autoApprove': JSON.stringify(approvals)
                }
            }]
        };
    }

    private generateConcurrencyConfigMaps(userConfig: any) {
        const semaphoreKeys = this.generateSemaphoreKeys(userConfig);
        const semaphoreData: Record<string, string> = {};
        
        // Add each semaphore key with count=1
        semaphoreKeys.forEach(key => {
            semaphoreData[key] = "1";
        });

        return {
            apiVersion: 'v1',
            kind: 'List',
            items: [{
                apiVersion: 'v1',
                kind: 'ConfigMap',
                metadata: {
                    name: 'concurrency-config'
                },
                data: {
                    // General concurrency settings
                    'concurrency.yaml': stringify({
                        maxConcurrentWorkflows: 5,
                        maxConcurrentSnapshots: 2,
                        maxConcurrentMigrations: 3
                    }),
                    // Semaphore keys with count=1
                    ...semaphoreData
                }
            }]
        };
    }

    private generateSemaphoreKeys(userConfig: any): string[] {
        if (!userConfig?.migrationConfigs) {
            return [];
        }

        const semaphoreKeys: string[] = [];

        for (const migrationConfig of userConfig.migrationConfigs) {
            const sourceName = migrationConfig.fromSource;
            const sourceCluster = userConfig.sourceClusters?.[sourceName];
            
            if (!sourceCluster || !migrationConfig.snapshotExtractAndLoadConfigs) {
                continue;
            }

            const sourceVersion = sourceCluster.version || "";
            
            for (const snapshotConfig of migrationConfig.snapshotExtractAndLoadConfigs) {
                // Apply same logic as generateSemaphoreConfig
                const isLegacyVersion = /^(?:ES [1-7]|OS 1)(?:\.[0-9]+)*$/.test(sourceVersion);
                
                if (isLegacyVersion) {
                    // Legacy versions: shared semaphore per source cluster
                    const key = `snapshot-legacy-${sourceName}`;
                    if (!semaphoreKeys.includes(key)) {
                        semaphoreKeys.push(key);
                    }
                } else {
                    // Modern versions: unique key per snapshot (no effective limiting)
                    const snapshotName = snapshotConfig.snapshotConfig?.snapshotNameConfig?.snapshotNamePrefix || 
                                       snapshotConfig.snapshotConfig?.snapshotNameConfig?.externallyManagedSnapshot || 
                                       'unknown';
                    const key = `snapshot-modern-${sourceName}-${snapshotName}`;
                    semaphoreKeys.push(key);
                }
            }
        }

        return semaphoreKeys;
    }
}
