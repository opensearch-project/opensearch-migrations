import {deepStrict, StreamSchemaParser} from "./StreamSchemaTransformer";
import {
    ARGO_WORKFLOW_SCHEMA,
    PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
} from "@opensearch-migrations/schemas";
import {Etcd3} from "etcd3";
import {z} from "zod";
import _ from 'lodash';

/** etcd connection options */
export interface EtcdOptions {
    endpoints: string[];           // e.g., "http://127.0.0.1:2379" or array
    username: string;
    password: string;
}

export class MigrationInitializer {
    readonly client: Etcd3;
    readonly loader: StreamSchemaParser<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;
    constructor(etcdSettings: EtcdOptions, public readonly prefix: string) {
        console.log("Initializing with " + JSON.stringify(etcdSettings));
        this.client = new Etcd3({
            hosts: etcdSettings.endpoints,
            auth: {username: etcdSettings.username, password: etcdSettings.password}
        });
        this.loader = new StreamSchemaParser(deepStrict(PARAMETERIZED_MIGRATION_CONFIG_ARRAYS));
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
            await this.client.put(`/${this.prefix}/workflow/info/prefix`).value(this.prefix);
            await this.client.put(`/${this.prefix}/workflow/info/started`).value(
                Math.floor(Date.now() / 1000).toString()
            );

            const targetsMap = _.groupBy(workflows, w=>w.targetConfig.name);

            // Initialize target latches
            for (const [targetName, list] of Object.entries(targetsMap)) {
                const processorCount = this.calculateProcessorCount(list);
                console.log(`Total processor count: ${processorCount}`);

                await this.client.put(`/${this.prefix}/workflow/targets/${targetName}/latch`)
                    .value(processorCount.toString());

                console.log(`Target ${targetName} (${targetName}) latch initialized with count ${processorCount}`);
            }

            console.log(`Etcd keys initialized with prefix: ${this.prefix}`);
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
}
