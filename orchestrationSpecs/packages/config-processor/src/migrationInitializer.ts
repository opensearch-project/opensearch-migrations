import {StreamSchemaParser} from "./streamSchemaTransformer";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {
    ARGO_MIGRATION_CONFIG,
    ARGO_WORKFLOW_SCHEMA, K8S_NAMING_PATTERN
} from "@opensearch-migrations/schemas";
import {stringify} from "yaml";
import * as fs from "fs/promises";
import * as path from "path";
import {scrapeApprovals} from "./formatApprovals";
import {setNamesInUserConfig} from "./migrationConfigTransformer";
import { generateSemaphoreKey } from './semaphoreUtils';

export class MigrationInitializer {
    readonly loader: StreamSchemaParser<typeof ARGO_MIGRATION_CONFIG>;
    readonly transformer: MigrationConfigTransformer;
    constructor() {
        // this is an interactive process meant to be used at the beginning of a workflow -
        // no reason to try excessively.  Modest retries and then the user can resubmit or investigate.
        this.loader = new StreamSchemaParser(ARGO_MIGRATION_CONFIG);
        this.transformer = new MigrationConfigTransformer();
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
        const crdResources = this.generateCRDResources(workflows);
        
        return {
            workflows,
            approvalConfigMaps,
            concurrencyConfigMaps,
            crdResources
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

        // 4. Write CRD resources
        const crdPath = path.join(outputDir, 'crdResources.yaml');
        await fs.writeFile(crdPath, stringify(bundle.crdResources));
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

    private generateCRDResources(workflows: ARGO_WORKFLOW_SCHEMA) {
        const CRD_API_VERSION = 'migrations.opensearch.org/v1alpha1';
        const items: any[] = [];

        // CapturedTraffic resources from proxies
        for (const proxy of workflows.proxies) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'CapturedTraffic',
                metadata: { name: proxy.name },
                spec: {},
                status: { phase: 'Initialized' }
            });
        }

        // DataSnapshot resources from snapshots
        for (const snapshot of workflows.snapshots) {
            for (const item of snapshot.createSnapshotConfig) {
                items.push({
                    apiVersion: CRD_API_VERSION,
                    kind: 'DataSnapshot',
                    metadata: { name: item.snapshotPrefix },
                    spec: {},
                    status: { phase: 'Initialized' }
                });
            }
        }

        // SnapshotMigration resources from snapshotMigrations
        for (const migration of workflows.snapshotMigrations) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'SnapshotMigration',
                metadata: { name: migration.label },
                spec: {},
                status: { phase: 'Initialized' }
            });
        }

        return {
            apiVersion: 'v1',
            kind: 'List',
            items
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
