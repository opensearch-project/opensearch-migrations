import {StreamSchemaParser} from "./streamSchemaTransformer";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {
    ARGO_MIGRATION_CONFIG_PRE_ENRICH, NAMED_KAFKA_CLUSTER_CONFIG,
} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {stringify} from "yaml";
import * as fs from "fs/promises";
import * as path from "path";
import {scrapeApprovals} from "./formatApprovals";
import {setNamesInUserConfig} from "./migrationConfigTransformer";
import { generateSemaphoreKey } from './semaphoreUtils';

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type KafkaClusterConfig = NonNullable<WorkflowConfig["kafkaClusters"]>[number];
type ProxyConfig = WorkflowConfig["proxies"][number];
type SnapshotConfig = WorkflowConfig["snapshots"][number];
type SnapshotItemConfig = SnapshotConfig["createSnapshotConfig"][number];
type SnapshotMigrationConfig = WorkflowConfig["snapshotMigrations"][number];
type SnapshotMigrationItemConfig = SnapshotMigrationConfig["migrations"][number];
type ReplayConfig = WorkflowConfig["trafficReplays"][number];

// const a: KafkaClusterConfig = { vers };
// const b: WorkflowConfig = { snapshotMigrations: [{ label: "" }] }
//const b: KafkaClusterConfig = { kafkaClusters: [{ version2: "" }] }

type StatusPatchableResource = {
    apiVersion: string;
    kind: string;
    metadata: {name: string};
    status?: Record<string, unknown>;
};

export class MigrationInitializer {
    readonly loader: StreamSchemaParser<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
    readonly transformer: MigrationConfigTransformer;
    constructor() {
        // this is an interactive process meant to be used at the beginning of a workflow -
        // no reason to try excessively.  Modest retries and then the user can resubmit or investigate.
        this.loader = new StreamSchemaParser(ARGO_MIGRATION_CONFIG_PRE_ENRICH);
        this.transformer = new MigrationConfigTransformer();
    }

    /**
     * Generate output files including workflow config, approval ConfigMaps, and concurrency ConfigMaps with semaphores
     */
    async generateOutputFiles(workflows: WorkflowConfig, outputDir: string, userConfig: any): Promise<void> {
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

        // 6. Write workflow config enrichment script for server-assigned CR UIDs
        const enrichScript = this.generateWorkflowUidEnrichmentScript(bundle.workflows);
        if (enrichScript !== null) {
            const enrichPath = path.join(outputDir, 'enrichWorkflowConfigWithUids.sh');
            await fs.writeFile(enrichPath, enrichScript, { mode: 0o755 });
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

    private makeApprovalGateName(parts: string[], action: string): string {
        return [...parts, action].join('.');
    }

    private generateCRDResources(workflows: WorkflowConfig) {
        const CRD_API_VERSION = 'migrations.opensearch.org/v1alpha1';
        const items: any[] = [];

        // KafkaCluster resources from workflow-managed Kafka clusters
        for (const kafkaCluster of (workflows.kafkaClusters ?? []) as KafkaClusterConfig[]) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'KafkaCluster',
                metadata: { name: kafkaCluster.name },
                spec: { dependsOn: [] },
                status: { phase: 'Initialized', configChecksum: kafkaCluster.configChecksum }
            });
        }

        // CapturedTraffic resources from proxies
        for (const proxy of (workflows.proxies ?? []) as ProxyConfig[]) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'CapturedTraffic',
                metadata: { name: proxy.name },
                spec: { dependsOn: [proxy.kafkaConfig.label] },
                status: { phase: 'Initialized', configChecksum: proxy.configChecksum }
            });
        }

        // DataSnapshot resources from snapshots
        for (const snapshot of (workflows.snapshots ?? []) as SnapshotConfig[]) {
            for (const item of snapshot.createSnapshotConfig as SnapshotItemConfig[]) {
                items.push({
                    apiVersion: CRD_API_VERSION,
                    kind: 'DataSnapshot',
                    metadata: { name: this.makeCrdName(snapshot.sourceConfig.label, item.label) },
                    spec: { dependsOn: (item.dependsOnProxySetups ?? []).map(dep => dep.name) },
                    status: { phase: 'Initialized', configChecksum: item.configChecksum }
                });
            }
        }

        // SnapshotMigration resources from snapshotMigrations
        for (const migration of (workflows.snapshotMigrations ?? []) as SnapshotMigrationConfig[]) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'SnapshotMigration',
                metadata: { name: this.makeCrdName(migration.sourceLabel, migration.targetConfig.label, migration.label) },
                spec: {
                    dependsOn: "dataSnapshotResourceName" in migration.snapshotNameResolution
                        ? [migration.snapshotNameResolution.dataSnapshotResourceName]
                        : []
                },
                status: { phase: 'Initialized', configChecksum: migration.configChecksum }
            });

            for (const migrationItem of migration.migrations as SnapshotMigrationItemConfig[]) {
                const approvalNameParts = [
                    migration.sourceLabel,
                    migration.targetConfig.label,
                    migration.snapshotConfig.label,
                    migrationItem.label,
                ];
                items.push({
                    apiVersion: CRD_API_VERSION,
                    kind: 'ApprovalGate',
                    metadata: { name: this.makeApprovalGateName(approvalNameParts, 'evaluateMetadata') },
                    spec: { dependsOn: [this.makeCrdName(migration.sourceLabel, migration.targetConfig.label, migration.label)] },
                    status: { phase: 'Initialized' }
                });
                items.push({
                    apiVersion: CRD_API_VERSION,
                    kind: 'ApprovalGate',
                    metadata: { name: this.makeApprovalGateName(approvalNameParts, 'migrateMetadata') },
                    spec: { dependsOn: [this.makeCrdName(migration.sourceLabel, migration.targetConfig.label, migration.label)] },
                    status: { phase: 'Initialized' }
                });
            }
        }

        // TrafficReplay resources from trafficReplays
        for (const replay of (workflows.trafficReplays ?? []) as ReplayConfig[]) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'TrafficReplay',
                metadata: { name: replay.name },
                spec: {
                    dependsOn: [
                        replay.fromProxy,
                        ...((replay.dependsOnSnapshotMigrations ?? []) as ReplayConfig["dependsOnSnapshotMigrations"]).map(dep =>
                            this.makeCrdName(dep.source, replay.toTarget.label, dep.snapshot))
                    ]
                },
                status: { phase: 'Initialized', configChecksum: replay.configChecksum }
            });
        }

        return {
            apiVersion: 'v1',
            kind: 'List',
            items
        };
    }

    private generateWorkflowUidEnrichmentScript(workflows: WorkflowConfig): string | null {
        const kafkaClusters = (workflows.kafkaClusters ?? []) as KafkaClusterConfig[];
        const proxies = workflows.proxies as ProxyConfig[];
        const snapshotMigrations = workflows.snapshotMigrations as SnapshotMigrationConfig[];
        const trafficReplays = workflows.trafficReplays as ReplayConfig[];
        const snapshotMigrationResources = snapshotMigrations.map(migration => ({
            name: this.makeCrdName(
                migration.sourceLabel,
                migration.targetConfig.label,
                migration.label
            )
        }));
        if (kafkaClusters.length === 0 && proxies.length === 0 && trafficReplays.length === 0) {
            return null;
        }

        const shellVar = (prefix: string, name: string) =>
            `${prefix}_${name.replace(/[^A-Za-z0-9_]/g, "_")}`;
        const kubectlGetUid = (resource: string, name: string, varName: string) =>
            `${varName}="$(kubectl get ${resource}/${name} -o jsonpath='{.metadata.uid}')"`;

        const uidLookups = [
            ...kafkaClusters.map(cluster =>
                kubectlGetUid(
                    'kafkaclusters.migrations.opensearch.org',
                    cluster.name,
                    shellVar('kafka', cluster.name)
                )),
            ...proxies.map(proxy =>
                kubectlGetUid(
                    'capturedtraffics.migrations.opensearch.org',
                    proxy.name,
                    shellVar('proxy', proxy.name)
                )),
            ...snapshotMigrationResources.map(migration =>
                kubectlGetUid(
                    'snapshotmigrations.migrations.opensearch.org',
                    migration.name,
                    shellVar('snapshot_migration', migration.name)
                )),
            ...trafficReplays.map(replay =>
                kubectlGetUid(
                    'trafficreplays.migrations.opensearch.org',
                    replay.name,
                    shellVar('replay', replay.name)
                )),
        ];

        const uidMapArgs = [
            ...kafkaClusters.map(cluster => `  --arg ${shellVar('kafka', cluster.name)} "$${shellVar('kafka', cluster.name)}"`),
            ...proxies.map(proxy => `  --arg ${shellVar('proxy', proxy.name)} "$${shellVar('proxy', proxy.name)}"`),
            ...snapshotMigrationResources.map(migration =>
                `  --arg ${shellVar('snapshot_migration', migration.name)} "$${shellVar('snapshot_migration', migration.name)}"`
            ),
            ...trafficReplays.map(replay => `  --arg ${shellVar('replay', replay.name)} "$${shellVar('replay', replay.name)}"`),
        ];

        const mapEntries = (items: {name: string}[], prefix: string) =>
            items.map(item => `      ${JSON.stringify(item.name)}: $${shellVar(prefix, item.name)}`).join(',\n');

        const uidMapJson = [
            "uid_map_json=\"$(jq -n \\",
            ...uidMapArgs.map(arg => `${arg} \\`),
            "  '{",
            "    kafkaClusters: {",
            mapEntries(kafkaClusters, 'kafka'),
            "    },",
            "    proxies: {",
            mapEntries(proxies, 'proxy'),
            "    },",
            "    snapshotMigrations: {",
            mapEntries(snapshotMigrationResources, 'snapshot_migration'),
            "    },",
            "    trafficReplays: {",
            mapEntries(trafficReplays, 'replay'),
            "    }",
            "  }'",
            ")\"",
        ].join('\n');

        return [
            "#!/bin/sh",
            "set -e",
            "",
            "CONFIG_PATH=\"${1:?Usage: $0 <workflowMigration.config.yaml>}\"",
            "",
            ...uidLookups,
            "",
            uidMapJson,
            "",
            "tmp_file=\"$(mktemp)\"",
            "trap 'rm -f \"$tmp_file\"' EXIT",
            "",
            "jq --argjson uids \"$uid_map_json\" '",
            "  .kafkaClusters |= ((. // []) | map(. + {resourceUid: $uids.kafkaClusters[.name]}))",
            "  | .proxies |= ((. // []) | map(. + {resourceUid: $uids.proxies[.name]}))",
            "  | .snapshotMigrations |= ((. // []) | map(. + {resourceUid: $uids.snapshotMigrations[(.sourceLabel + \"-\" + .targetConfig.label + \"-\" + .label)]}))",
            "  | .trafficReplays |= ((. // []) | map(. + {resourceUid: $uids.trafficReplays[.name]}))",
            "' \"$CONFIG_PATH\" > \"$tmp_file\"",
            "",
            "mv \"$tmp_file\" \"$CONFIG_PATH\"",
            "",
        ].join('\n');
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
