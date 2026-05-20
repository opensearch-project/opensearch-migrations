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
import { generateSemaphoreKey, resolveSerializeSnapshotCreation } from './semaphoreUtils';

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type KafkaClusterConfig = NonNullable<WorkflowConfig["kafkaClusters"]>[number];
type ProxyConfig = WorkflowConfig["proxies"][number];
type SnapshotConfig = WorkflowConfig["snapshots"][number];
type SnapshotItemConfig = SnapshotConfig["createSnapshotConfig"][number];
type SnapshotMigrationConfig = WorkflowConfig["snapshotMigrations"][number];
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

const CRD_KIND_TO_PLURAL: Record<string, string> = {
    ApprovalGate: 'approvalgates',
    CaptureProxy: 'captureproxies',
    CapturedTraffic: 'capturedtraffics',
    DataSnapshot: 'datasnapshots',
    KafkaCluster: 'kafkaclusters',
    SnapshotMigration: 'snapshotmigrations',
    TrafficReplay: 'trafficreplays',
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
    async generateOutputFiles(workflows: WorkflowConfig, outputDir: string, userConfig: any, workflowName?: string): Promise<void> {
        const bundle = await this.generateMigrationBundle(userConfig, workflowName);
        await this.writeBundleToFiles(bundle, outputDir);
    }

    /**
     * Generate all migration artifacts from user config
     */
    async generateMigrationBundle(userConfig: any, workflowName?: string) {
        // Transform user config to workflow config
        const workflows = await this.transformer.processFromObject(userConfig);
        
        // Generate ConfigMaps
        const approvalConfigMaps = this.generateApprovalConfigMaps(userConfig);
        const concurrencyConfigMaps = this.generateConcurrencyConfigMaps(userConfig);
        const crdResources = this.generateCRDResources(workflows, workflowName);
        const warnings = this.generateWarnings(workflows);
        
        return {
            workflows,
            approvalConfigMaps,
            concurrencyConfigMaps,
            crdResources,
            warnings
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

        // 2. Write individual resource files and the handler script
        const resourcesDir = path.join(outputDir, 'resources');
        await fs.mkdir(resourcesDir, { recursive: true });

        const allItems = bundle.crdResources.items || [];
        const configMapItems = [
            ...(bundle.approvalConfigMaps.items || []),
            ...(bundle.concurrencyConfigMaps.items || []),
        ];

        type ResourceEntry = {
            file: string;
            kind: string;
            name: string;
            category: 'approvalgate' | 'rootcr' | 'configmap';
            hasStatus: boolean;
        };
        const entries: ResourceEntry[] = [];

        let idx = 0;
        for (const item of allItems) {
            const name = item.metadata.name;
            const kind = item.kind;
            const category = kind === 'ApprovalGate' ? 'approvalgate' as const : 'rootcr' as const;
            const filename = `${String(idx).padStart(3, '0')}-${kind.toLowerCase()}-${name}.yaml`;
            // Write resource without status (status must be patched separately via subresource)
            const { status, ...resourceWithoutStatus } = item;
            await fs.writeFile(path.join(resourcesDir, filename), stringify(resourceWithoutStatus));
            entries.push({ file: filename, kind, name, category, hasStatus: status !== undefined });
            idx++;
        }
        for (const item of configMapItems) {
            const name = item.metadata.name;
            const kind = item.kind;
            const filename = `${String(idx).padStart(3, '0')}-${kind.toLowerCase()}-${name}.yaml`;
            await fs.writeFile(path.join(resourcesDir, filename), stringify(item));
            entries.push({ file: filename, kind, name, category: 'configmap', hasStatus: false });
            idx++;
        }

        // Write the handler script
        const handlerScript = this.generateHandleK8sResourcesScript(entries, allItems);
        await fs.writeFile(path.join(outputDir, 'handleK8sResources.sh'), handlerScript, { mode: 0o755 });

        // 3. Write workflow config enrichment script for server-assigned CR UIDs
        const enrichScript = this.generateWorkflowUidEnrichmentScript(bundle.workflows);
        if (enrichScript !== null) {
            const enrichPath = path.join(outputDir, 'enrichWorkflowConfigWithUids.sh');
            await fs.writeFile(enrichPath, enrichScript, { mode: 0o755 });
        }

        // 4. Write warnings file if any
        if (bundle.warnings && bundle.warnings.length > 0) {
            await fs.writeFile(path.join(outputDir, 'warnings.json'), JSON.stringify(bundle.warnings));
        }
    }

    private generateHandleK8sResourcesScript(
        entries: { file: string; kind: string; name: string; category: string; hasStatus: boolean }[],
        allItems: any[]
    ): string {
        const lines: string[] = [
            '#!/bin/sh',
            'set -e',
            '',
            'SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"',
            'RESOURCES_DIR="$SCRIPT_DIR/resources"',
            '',
        ];

        // Approval gate label cleanup (delete by label before individual creates)
        const gateEntries = entries.filter(e => e.category === 'approvalgate');
        if (gateEntries.length > 0) {
            const firstGate = allItems.find((item: any) => item.kind === 'ApprovalGate');
            const labelKey = MigrationInitializer.APPROVAL_GATE_LABEL_KEY;
            const labelValue = firstGate?.metadata?.labels?.[labelKey];
            if (labelValue) {
                lines.push(`# Clean up stale approval gates by label`);
                lines.push(`kubectl delete approvalgates.${MigrationInitializer.CRD_GROUP} -l '${labelKey}=${labelValue}' --ignore-not-found`);
                lines.push('');
            }
            // Fallback: delete each known gate by name
            lines.push('# Fallback: delete known gates by name');
            for (const entry of gateEntries) {
                lines.push(`kubectl delete approvalgates.${MigrationInitializer.CRD_GROUP}/${entry.name} --ignore-not-found`);
            }
            lines.push('');
        }

        lines.push('pids=""');
        lines.push('');

        for (const entry of entries) {
            const statusItem = allItems.find((item: any) =>
                item.kind === entry.kind && item.metadata.name === entry.name);
            const statusPatchCmd = entry.hasStatus && statusItem?.status
                ? this.makeStatusPatchCommand(statusItem)
                : null;

            const errFile = `/tmp/k8s-handler-${entry.kind}-${entry.name}.$$`;

            if (entry.category === 'approvalgate') {
                lines.push(`# ApprovalGate: ${entry.name}`);
                lines.push('(');
                lines.push(`  kubectl create -f "$RESOURCES_DIR/${entry.file}"`);
                if (statusPatchCmd) lines.push(`  ${statusPatchCmd}`);
                lines.push(`  echo "RESULT ApprovalGate ${entry.name} CREATED"`);
                lines.push(') &');
                lines.push('pids="$pids $!"');

            } else if (entry.category === 'rootcr') {
                lines.push(`# Root CR: ${entry.kind} ${entry.name}`);
                lines.push('(');
                lines.push(`  err_file="${errFile}"`);
                lines.push(`  if kubectl create -f "$RESOURCES_DIR/${entry.file}" 2>"$err_file"; then`);
                if (statusPatchCmd) lines.push(`    ${statusPatchCmd}`);
                lines.push(`    echo "RESULT ${entry.kind} ${entry.name} CREATED"`);
                lines.push(`  elif grep -q 'AlreadyExists' "$err_file"; then`);
                lines.push(`    echo "RESULT ${entry.kind} ${entry.name} ALREADY_EXISTS - reusing existing resource"`);
                lines.push(`  else`);
                lines.push(`    cat "$err_file" >&2`);
                lines.push(`    echo "RESULT ${entry.kind} ${entry.name} FAILED - check kubectl error output above" >&2`);
                lines.push(`    rm -f "$err_file"`);
                lines.push(`    exit 1`);
                lines.push(`  fi`);
                lines.push(`  rm -f "$err_file"`);
                lines.push(') &');
                lines.push('pids="$pids $!"');

            } else if (entry.category === 'configmap') {
                lines.push(`# ConfigMap: ${entry.name}`);
                lines.push('(');
                lines.push(`  kubectl apply -f "$RESOURCES_DIR/${entry.file}"`);
                lines.push(`  echo "RESULT ${entry.kind} ${entry.name} APPLIED"`);
                lines.push(') &');
                lines.push('pids="$pids $!"');
            }
            lines.push('');
        }

        lines.push('for pid in $pids; do');
        lines.push('  wait "$pid"');
        lines.push('done');
        lines.push('');

        return lines.join('\n');
    }

    private makeStatusPatchCommand(item: StatusPatchableResource): string {
        const group = item.apiVersion.split('/')[0];
        const pluralName = CRD_KIND_TO_PLURAL[item.kind];
        if (!pluralName) {
            throw new Error(`No plural mapping defined for CRD kind: ${item.kind}`);
        }
        const plural = `${pluralName}.${group}`;
        const patch = JSON.stringify({ status: item.status });
        return `kubectl patch ${plural}/${item.metadata.name} --subresource=status --type=merge -p '${patch}'`;
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

    static readonly APPROVAL_GATE_LABEL_KEY = 'migrations.opensearch.org/workflow';
    static readonly GATE_LABEL_RESOURCE_KIND = 'migrations.opensearch.org/resource-kind';
    static readonly GATE_LABEL_RESOURCE_NAME = 'migrations.opensearch.org/resource-name';
    static readonly GATE_LABEL_SOURCE = 'migrations.opensearch.org/source';
    static readonly GATE_LABEL_TARGET = 'migrations.opensearch.org/target';
    static readonly GATE_LABEL_SNAPSHOT = 'migrations.opensearch.org/snapshot';
    static readonly GATE_LABEL_MIGRATION = 'migrations.opensearch.org/migration';
    static readonly OUTPUT_LABEL_MIGRATION = 'migrations.opensearch.org/from-snapshot-migration';
    static readonly OUTPUT_LABEL_TASK = 'migrations.opensearch.org/task';
    static readonly OUTPUT_LABEL_KAFKA_CLUSTER = 'migrations.opensearch.org/kafka-cluster';
    static readonly WORKFLOW_LABEL = 'workflows.argoproj.io/workflow';
    static readonly STRIMZI_CLUSTER_LABEL = 'strimzi.io/cluster';
    static readonly CRD_GROUP = 'migrations.opensearch.org';
    static readonly CRD_API_VERSION = `${MigrationInitializer.CRD_GROUP}/v1alpha1`;

    private makeCrdName(...labels: string[]): string {
        return labels.join('-');
    }

    private makeApprovalGateResource(nameParts: string[], labels?: Record<string, string>) {
        return {
            apiVersion: MigrationInitializer.CRD_API_VERSION,
            kind: 'ApprovalGate',
            metadata: {
                name: nameParts.join('.').toLowerCase(),
                ...(labels && Object.keys(labels).length > 0 && { labels }),
            },
            spec: {},
            status: { phase: 'Initialized' }
        };
    }

    private generateCRDResources(workflows: WorkflowConfig, workflowName?: string) {
        const CRD_API_VERSION = MigrationInitializer.CRD_API_VERSION;
        const baseGateLabels: Record<string, string> = workflowName
            ? { [MigrationInitializer.APPROVAL_GATE_LABEL_KEY]: workflowName }
            : {};
        const baseResourceLabels: Record<string, string> = workflowName
            ? { [MigrationInitializer.WORKFLOW_LABEL]: workflowName }
            : {};
        // Merge baseGateLabels with the per-gate context labels.
        const gateLabels = (extra: Record<string, string | undefined>) => {
            const merged: Record<string, string> = { ...baseGateLabels };
            for (const [k, v] of Object.entries(extra)) {
                if (v !== undefined && v !== '') merged[k] = v;
            }
            return merged;
        };
        const items: any[] = [];

        // KafkaCluster resources from workflow-managed Kafka clusters
        for (const kafkaCluster of (workflows.kafkaClusters ?? []) as KafkaClusterConfig[]) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'KafkaCluster',
                metadata: {
                    name: kafkaCluster.name,
                    labels: {
                        ...baseResourceLabels,
                        [MigrationInitializer.STRIMZI_CLUSTER_LABEL]: kafkaCluster.name,
                    },
                },
                spec: {},
                status: { phase: 'Initialized', configChecksum: '' }
            });

            const kcLabels = gateLabels({
                [MigrationInitializer.GATE_LABEL_RESOURCE_KIND]: 'KafkaCluster',
                [MigrationInitializer.GATE_LABEL_RESOURCE_NAME]: kafkaCluster.name,
            });

            // VAP retry gate for the root KafkaCluster CR reconcile.
            // Sub-operations (nodepool, user, topic) are managed by Strimzi
            // and do not go through the migrations VAP — no gates needed.
            // Topic-level concerns are covered by the CapturedTraffic gate.
            items.push(this.makeApprovalGateResource(
                ['kafkacluster', kafkaCluster.name, 'vapretry'], kcLabels));
        }

        // CapturedTraffic (topic/stream) and CaptureProxy resources from proxies
        for (const proxy of (workflows.proxies ?? []) as ProxyConfig[]) {
            const topicCrName = proxy.name + '-topic';
            const proxySource = proxy.sourceConfig?.label;

            // CapturedTraffic: topic/stream contract
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'CapturedTraffic',
                metadata: {
                    name: topicCrName,
                    labels: {
                        ...baseResourceLabels,
                        ...(proxySource && { [MigrationInitializer.GATE_LABEL_SOURCE]: proxySource }),
                        [MigrationInitializer.OUTPUT_LABEL_KAFKA_CLUSTER]: proxy.kafkaConfig.label,
                    }
                },
                spec: { dependsOn: [proxy.kafkaConfig.label] },
                status: { phase: 'Initialized', configChecksum: '' }
            });

            // CaptureProxy: proxy deployment contract
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'CaptureProxy',
                metadata: {
                    name: proxy.name,
                    labels: {
                        ...baseResourceLabels,
                        ...(proxySource && { [MigrationInitializer.GATE_LABEL_SOURCE]: proxySource }),
                        [MigrationInitializer.OUTPUT_LABEL_TASK]: 'captureProxy',
                    }
                },
                spec: { dependsOn: [topicCrName] },
                status: { phase: 'Initialized', configChecksum: '' }
            });

            // VAP retry gates
            items.push(this.makeApprovalGateResource(
                ['capturedtraffic', topicCrName, 'vapretry'],
                gateLabels({
                    [MigrationInitializer.GATE_LABEL_RESOURCE_KIND]: 'CapturedTraffic',
                    [MigrationInitializer.GATE_LABEL_RESOURCE_NAME]: topicCrName,
                    [MigrationInitializer.GATE_LABEL_SOURCE]: proxySource,
                })
            ));
            items.push(this.makeApprovalGateResource(
                ['captureproxy', proxy.name, 'vapretry'],
                gateLabels({
                    [MigrationInitializer.GATE_LABEL_RESOURCE_KIND]: 'CaptureProxy',
                    [MigrationInitializer.GATE_LABEL_RESOURCE_NAME]: proxy.name,
                    [MigrationInitializer.GATE_LABEL_SOURCE]: proxySource,
                })
            ));
        }

        // DataSnapshot resources from snapshots
        for (const snapshot of (workflows.snapshots ?? []) as SnapshotConfig[]) {
            for (const item of snapshot.createSnapshotConfig as SnapshotItemConfig[]) {
                items.push({
                    apiVersion: CRD_API_VERSION,
                    kind: 'DataSnapshot',
                    metadata: {
                        name: this.makeCrdName(snapshot.sourceConfig.label, item.label),
                        labels: {
                            ...baseResourceLabels,
                            [MigrationInitializer.GATE_LABEL_SOURCE]: snapshot.sourceConfig.label,
                            [MigrationInitializer.GATE_LABEL_SNAPSHOT]: item.label,
                        }
                    },
                    spec: { dependsOn: (item.dependsOnProxySetups ?? []).map(dep => dep.name) },
                    status: { phase: 'Initialized', configChecksum: '' }
                });
            }
        }

        // SnapshotMigration resources from snapshotMigrations
        for (const migration of (workflows.snapshotMigrations ?? []) as SnapshotMigrationConfig[]) {
            const snapshotMigrationName = this.makeCrdName(
                migration.sourceLabel,
                migration.targetConfig.label,
                migration.label,
                migration.migrationLabel
            );
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'SnapshotMigration',
                metadata: {
                    name: snapshotMigrationName,
                    labels: {
                        ...baseResourceLabels,
                        [MigrationInitializer.GATE_LABEL_SOURCE]: migration.sourceLabel,
                        [MigrationInitializer.GATE_LABEL_TARGET]: migration.targetConfig.label,
                        [MigrationInitializer.GATE_LABEL_SNAPSHOT]: migration.snapshotConfig.label,
                        [MigrationInitializer.OUTPUT_LABEL_MIGRATION]: migration.migrationLabel,
                    }
                },
                spec: {
                },
                status: { phase: 'Initialized', configChecksum: '' }
            });

            const migLabels = gateLabels({
                [MigrationInitializer.GATE_LABEL_RESOURCE_KIND]: 'SnapshotMigration',
                [MigrationInitializer.GATE_LABEL_RESOURCE_NAME]: snapshotMigrationName,
                [MigrationInitializer.GATE_LABEL_SOURCE]: migration.sourceLabel,
                [MigrationInitializer.GATE_LABEL_TARGET]: migration.targetConfig.label,
                [MigrationInitializer.GATE_LABEL_SNAPSHOT]: migration.snapshotConfig.label,
                [MigrationInitializer.GATE_LABEL_MIGRATION]: migration.migrationLabel,
            });

            // VAP retry gate for the root SnapshotMigration CR reconcile
            items.push(this.makeApprovalGateResource(
                ['snapshotmigration', snapshotMigrationName, 'vapretry'], migLabels));

            const approvalNameParts = [
                migration.sourceLabel,
                migration.targetConfig.label,
                migration.snapshotConfig.label,
                migration.migrationLabel,
            ];
            const resourcePath = this.makeCrdName(...approvalNameParts);
            items.push(this.makeApprovalGateResource(
                ['evaluatemetadata', resourcePath], migLabels));
            items.push(this.makeApprovalGateResource(
                ['migratemetadata', resourcePath], migLabels));
        }

        // TrafficReplay resources from trafficReplays
        for (const replay of (workflows.trafficReplays ?? []) as ReplayConfig[]) {
            items.push({
                apiVersion: CRD_API_VERSION,
                kind: 'TrafficReplay',
                metadata: {
                    name: replay.name,
                    labels: {
                        ...baseResourceLabels,
                        [MigrationInitializer.GATE_LABEL_SOURCE]: replay.sourceLabel,
                        [MigrationInitializer.GATE_LABEL_TARGET]: replay.toTarget.label,
                        [MigrationInitializer.OUTPUT_LABEL_TASK]: 'trafficReplayer',
                    }
                },
                spec: {
                    dependsOn: [
                        replay.fromProxy,
                        ...((replay.dependsOnSnapshotMigrations ?? []) as ReplayConfig["dependsOnSnapshotMigrations"]).map(dep =>
                            this.makeCrdName(dep.source, replay.toTarget.label, dep.snapshot, dep.migrationLabel))
                    ]
                },
                status: { phase: 'Initialized', configChecksum: '' }
            });

            // VAP retry gate for replay
            items.push(this.makeApprovalGateResource(
                ['trafficreplay', replay.name, 'vapretry'],
                gateLabels({
                    [MigrationInitializer.GATE_LABEL_RESOURCE_KIND]: 'TrafficReplay',
                    [MigrationInitializer.GATE_LABEL_RESOURCE_NAME]: replay.name,
                    [MigrationInitializer.GATE_LABEL_TARGET]: replay.toTarget.label,
                })
            ));
        }

        return {
            apiVersion: 'v1',
            kind: 'List',
            items
        };
    }

    private generateWarnings(workflows: WorkflowConfig): string[] {
        const warnings: string[] = [];
        for (const cluster of (workflows.kafkaClusters ?? []) as KafkaClusterConfig[]) {
            const deleteClaim = (cluster as any).config?.nodePoolSpecOverrides?.storage?.deleteClaim;
            if (deleteClaim === false || deleteClaim === undefined) {
                warnings.push(
                    `⚠️  Kafka cluster '${cluster.name}' has deleteClaim: ${deleteClaim ?? 'unset (defaults to false)'}. ` +
                    `Persistent volumes will not be automatically cleaned up on reset. ` +
                    `Use 'workflow reset --delete-storage' to avoid cluster ID conflicts on redeployment.`
                );
            }
        }
        return warnings;
    }

    generateApprovalGateCleanupScript(crdResources: { items: any[] }): string | null {
        const gates = crdResources.items.filter((item: any) => item.kind === 'ApprovalGate');
        if (gates.length === 0) return null;

        const plural = `approvalgates.${MigrationInitializer.CRD_GROUP}`;
        const lines = ['#!/bin/sh', 'set -x', ''];

        // Label-based cleanup — deletes all gates matching the workflow label
        const firstLabel = gates[0].metadata.labels;
        if (firstLabel?.[MigrationInitializer.APPROVAL_GATE_LABEL_KEY]) {
            const selector = `${MigrationInitializer.APPROVAL_GATE_LABEL_KEY}=${firstLabel[MigrationInitializer.APPROVAL_GATE_LABEL_KEY]}`;
            lines.push(`kubectl delete ${plural} -l '${selector}' --ignore-not-found`);
            lines.push('');
        }

        // Delete-by-name fallback for each known gate
        lines.push('# Fallback: delete by name in case labels were missing or changed');
        for (const gate of gates) {
            lines.push(`kubectl delete ${plural}/${gate.metadata.name} --ignore-not-found`);
        }
        lines.push('');

        return lines.join('\n');
    }

    private generateWorkflowUidEnrichmentScript(workflows: WorkflowConfig): string | null {
        const kafkaClusters = (workflows.kafkaClusters ?? []) as KafkaClusterConfig[];
        const proxies = workflows.proxies as ProxyConfig[];
        const snapshotMigrations = workflows.snapshotMigrations as SnapshotMigrationConfig[];
        const trafficReplays = workflows.trafficReplays as ReplayConfig[];
        const dataSnapshotResources = ((workflows.snapshots ?? []) as SnapshotConfig[])
            .flatMap(snapshot => (snapshot.createSnapshotConfig as SnapshotItemConfig[]).map(item => ({
                name: this.makeCrdName(snapshot.sourceConfig.label, item.label),
            })));
        const snapshotMigrationResources = snapshotMigrations.map(migration => ({
            name: this.makeCrdName(
                migration.sourceLabel,
                migration.targetConfig.label,
                migration.label,
                migration.migrationLabel
            )
        }));
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
                    'captureproxies.migrations.opensearch.org',
                    proxy.name,
                    shellVar('proxy', proxy.name)
                )),
            ...dataSnapshotResources.map(snapshot =>
                kubectlGetUid(
                    'datasnapshots.migrations.opensearch.org',
                    snapshot.name,
                    shellVar('data_snapshot', snapshot.name)
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
            ...dataSnapshotResources.map(snapshot =>
                `  --arg ${shellVar('data_snapshot', snapshot.name)} "$${shellVar('data_snapshot', snapshot.name)}"`
            ),
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
            "    dataSnapshots: {",
            mapEntries(dataSnapshotResources, 'data_snapshot'),
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
            "  | .proxies |= ((. // []) | map(. + {resourceUid: $uids.proxies[.name]} | .kafkaConfig += {clusterResourceUid: $uids.kafkaClusters[.kafkaConfig.label]}))",
            "  | .snapshots |= ((. // []) | map(. as $snapshot | .createSnapshotConfig |= ((. // []) | map(. + {resourceUid: $uids.dataSnapshots[($snapshot.sourceConfig.label + \"-\" + .label)]}))))",
            "  | .snapshotMigrations |= ((. // []) | map(. + {resourceUid: $uids.snapshotMigrations[(.sourceLabel + \"-\" + .targetConfig.label + \"-\" + .label + \"-\" + .migrationLabel)]}))",
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
            const serialize = resolveSerializeSnapshotCreation(
                sourceVersion,
                sourceCluster.snapshotInfo?.serializeSnapshotCreation
            );
            for (const snapshotName of Object.keys(sourceCluster.snapshotInfo?.snapshots || {})) {
                const key = generateSemaphoreKey(serialize, sourceName, snapshotName);
                if (!semaphoreKeys.includes(key)) {
                    semaphoreKeys.push(key);
                }
            }
        }

        return semaphoreKeys;
    }
}
