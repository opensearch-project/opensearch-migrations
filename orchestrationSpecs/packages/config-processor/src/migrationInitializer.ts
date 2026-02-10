import {StreamSchemaParser} from "./streamSchemaTransformer";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {
    ARGO_MIGRATION_CONFIG,
    ARGO_WORKFLOW_SCHEMA, K8S_NAMING_PATTERN
} from "@opensearch-migrations/schemas";
import { Etcd3, isRecoverableError } from "etcd3";
import {
    handleWhen,
    retry,
    circuitBreaker,
} from "cockatiel";
import { ExponentialBackoff } from "cockatiel/dist/backoff/ExponentialBackoff"; // path may vary
import {z} from "zod";
import {stringify} from "yaml";
import * as fs from "fs/promises";
import * as path from "path";
import {scrapeApprovals} from "./formatApprovals";
import {setNamesInUserConfig} from "./migrationConfigTransformer";
import { generateSemaphoreKey } from './semaphoreUtils';

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
    readonly loader: StreamSchemaParser<typeof ARGO_MIGRATION_CONFIG>;
    readonly transformer: MigrationConfigTransformer;
    constructor(etcdSettings: EtcdOptions, public readonly uniqueRunNonce: string) {
        if (!K8S_NAMING_PATTERN.test(uniqueRunNonce)) {
            throw new Error(`Illegal uniqueRunNonce argument.  Must match regex pattern ${K8S_NAMING_PATTERN}.`);
        }
        console.log("Initializing with " + JSON.stringify(etcdSettings));
        const recoverable = handleWhen((err) => isRecoverableError(err as any));
        // this is an interactive process meant to be used at the beginning of a workflow -
        // no reason to try excessively.  Modest retries and then the user can resubmit or investigate.
        this.client = new Etcd3({
            hosts: etcdSettings.endpoints,
            auth: etcdSettings.auth,
            faultHandling: {
                global: retry(recoverable, {
                    maxAttempts: 4,
                    backoff: new ExponentialBackoff({
                        initialDelay: 100, // ms
                        maxDelay: 2_000    // ms
                    } as any),
                })
            }
        });
        this.loader = new StreamSchemaParser(ARGO_MIGRATION_CONFIG);
        this.transformer = new MigrationConfigTransformer();
    }

    private calculateProcessorCount(targetMigrations: any[]): number {
        let count = 0;
        let hasReplayersConfigured = false;
        // for (const c of targetMigrations) {
        //     if (c. !== undefined) {
        //         hasReplayersConfigured = true;
        //     }
        //
        //     for (const snapshots of c.snapshotExtractAndLoadConfigArray??[]) {
        //         for (const m of snapshots.migrations) {
        //             count += 1;
        //         }
        //     }
        // }

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
                Object.groupBy(workflows.snapshotMigrations, w=> w.targetConfig.label);

            // Initialize target latches
            for (const [targetLabel, list] of Object.entries(targetsMap)) {
                const processorCount = list ? this.calculateProcessorCount(list) : 0;
                console.log(`Total processor count: ${processorCount}`);

                await this.client.put(`/${this.uniqueRunNonce}/workflow/targets/${targetLabel}/latch`)
                    .value(processorCount.toString());

                console.log(`Target ${targetLabel} latch initialized with count ${processorCount}`);
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
        const bundle = await this.generateMigrationBundle(userConfig);
        await this.writeBundleToFiles(bundle, outputDir);
    }

    /**
     * Generate all migration artifacts from user config
     */
    async generateMigrationBundle(userConfig: any) {
        // Transform user config to workflow config
        const workflows = await this.transformer.processFromObject(userConfig);
        
        // Generate ConfigMaps
        const approvalConfigMaps = this.generateApprovalConfigMaps(userConfig);
        const concurrencyConfigMaps = this.generateConcurrencyConfigMaps(userConfig);
        
        return {
            workflows,
            approvalConfigMaps,
            concurrencyConfigMaps
        };
    }

    /**
     * Write bundle to output files
     */
    private async writeBundleToFiles(bundle: any, outputDir: string): Promise<void> {
        await fs.mkdir(outputDir, { recursive: true });

        // 1. Write workflow configuration
        const workflowPath = path.join(outputDir, 'workflowMigration.config.yaml');
        await fs.writeFile(workflowPath, JSON.stringify(bundle.workflows, null, 2));

        // 2. Write approval config maps
        const approvalPath = path.join(outputDir, 'approvalConfigMaps.yaml');
        await fs.writeFile(approvalPath, stringify(bundle.approvalConfigMaps));

        // 3. Write concurrency config maps
        const concurrencyPath = path.join(outputDir, 'concurrencyConfigMaps.yaml');
        await fs.writeFile(concurrencyPath, stringify(bundle.concurrencyConfigMaps));
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
                    // Semaphore keys with count=1
                    ...semaphoreData
                }
            }]
        };
    }

    private generateSemaphoreKeys(userConfig: any): string[] {
        const semaphoreKeys: string[] = [];
        const sourceClusters = userConfig?.sourceClusters || {};

        for (const [sourceName, sourceCluster] of Object.entries<any>(sourceClusters)) {
            const sourceVersion = sourceCluster.version || "";
            for (const snapshotName of Object.keys(sourceCluster.snapshotInfo?.snapshots || {})) {
                const key = generateSemaphoreKey(sourceVersion, sourceName, snapshotName);
                if (!semaphoreKeys.includes(key)) {
                    semaphoreKeys.push(key);
                }
            }
        }

        return semaphoreKeys;
    }
}
