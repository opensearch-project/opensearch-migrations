import {StreamSchemaParser} from "./streamSchemaTransformer";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {
    ARGO_MIGRATION_CONFIG,
    ARGO_WORKFLOW_SCHEMA, K8S_NAMING_PATTERN,
} from "@opensearch-migrations/schemas";
import {stringify} from "yaml";
import * as fs from "fs/promises";
import * as path from "path";
import {scrapeApprovals} from "./formatApprovals";
import {setNamesInUserConfig} from "./migrationConfigTransformer";
import { generateSemaphoreKey } from './semaphoreUtils';

type StatusPatchableResource = {
    apiVersion: string;
    kind: string;
    metadata: {name: string};
    status?: Record<string, unknown>;
};

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

        // 5. Write CRD status patches (status subresource must be patched separately)
        const statusPatches = (bundle.crdResources.items || [])
            .filter((item: StatusPatchableResource) => item.status !== undefined)
            .map((item: StatusPatchableResource) => {
                const group = item.apiVersion.split('/')[0];
                const kind = item.kind.toLowerCase() + 's.' + group;
                const patch = JSON.stringify({ status: item.status });
                return `kubectl patch ${kind}/${item.metadata.name} --subresource=status --type=merge -p '${patch}'`;
            });
        if (statusPatches.length > 0) {
            const patchScript = '#!/bin/sh\nset -e\n' + statusPatches.join('\n') + '\n';
            const patchPath = path.join(outputDir, 'patchCrdStatus.sh');
            await fs.writeFile(patchPath, patchScript, { mode: 0o755 });
        }
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

    private makeCrdName(...labels: string[]): string {
        return labels.join('-');
    }

    private generateCRDResources(workflows: ARGO_WORKFLOW_SCHEMA) {
        const CRD_API_VERSION = 'migrations.opensearch.org/v1alpha1';
        const items: any[] = [];

        // KafkaCluster resources (root — no dependencies)
        for (const cluster of workflows.kafkaClusters ?? []) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'KafkaCluster',
                metadata: { name: cluster.name },
                spec: { dependsOn: [] },
                status: { phase: 'Initialized' }
            });
        }

        // CapturedTraffic resources from proxies
        for (const proxy of workflows.proxies ?? []) {
            const noCapture = (proxy as any).proxyConfig?.noCapture === true;
            const kafkaDep = (!noCapture && proxy.kafkaConfig?.label) ? [proxy.kafkaConfig.label] : [];
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'CapturedTraffic',
                metadata: { name: proxy.name },
                spec: { dependsOn: kafkaDep },
                status: { phase: 'Initialized', configChecksum: proxy.configChecksum }
            });
        }

        // DataSnapshot resources from snapshots
        for (const snapshot of workflows.snapshots ?? []) {
            for (const item of snapshot.createSnapshotConfig) {
                items.push({
                    apiVersion: CRD_API_VERSION,
                    kind: 'DataSnapshot',
                    metadata: { name: this.makeCrdName(snapshot.sourceConfig.label, item.label) },
                    spec: { dependsOn: item.dependsOnProxySetups ?? [] },
                    status: { phase: 'Initialized', configChecksum: item.configChecksum }
                });
            }
        }

        // SnapshotMigration resources from snapshotMigrations
        for (const migration of workflows.snapshotMigrations ?? []) {
            const snapshotDep = migration.snapshotNameResolution &&
                'dataSnapshotResourceName' in migration.snapshotNameResolution
                ? [migration.snapshotNameResolution.dataSnapshotResourceName]
                : [];
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'SnapshotMigration',
                metadata: { name: this.makeCrdName(migration.sourceLabel, migration.targetConfig.label, migration.label) },
                spec: { dependsOn: snapshotDep },
                status: { phase: 'Initialized', configChecksum: migration.configChecksum }
            });
        }

        // TrafficReplay resources from replayers
        for (const replay of workflows.trafficReplays ?? []) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'TrafficReplay',
                metadata: { name: replay.name },
                spec: { dependsOn: [replay.fromProxy] },
                status: { phase: 'Initialized', configChecksum: replay.configChecksum }
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
