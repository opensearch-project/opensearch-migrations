import { describe, it, expect } from '@jest/globals';
import { z } from 'zod';
import {
    FieldMeta,
    USER_PROXY_WORKFLOW_OPTIONS,
    USER_PROXY_PROCESS_OPTIONS,
    USER_REPLAYER_WORKFLOW_OPTIONS,
    USER_REPLAYER_PROCESS_OPTIONS,
    USER_RFS_WORKFLOW_OPTIONS,
    USER_RFS_PROCESS_OPTIONS,
} from '../src/userSchemas';

/**
 * Every field in a component schema must either:
 * - Have a changeRestriction annotation ('impossible' or 'gated'), OR
 * - Have a checksumFor annotation, OR
 * - Be explicitly listed as intentionally unannotated (safe/operational)
 *
 * This prevents someone from adding a new field and forgetting to classify it.
 */

// Fields that are intentionally safe/operational — no annotation needed.
// When adding a new field, you must either annotate it or add it here with a reason.
const INTENTIONALLY_UNANNOTATED: Record<string, Set<string>> = {
    'USER_PROXY_WORKFLOW_OPTIONS': new Set([
        'loggingConfigurationOverrideConfigMap',
        'podReplicas',
        'resources',
    ]),
    'USER_PROXY_PROCESS_OPTIONS': new Set([
        'otelCollectorEndpoint',
        'destinationConnectionPoolSize',
        'destinationConnectionPoolTimeout',
        'kafkaClientId',
        'numThreads',
        'sslConfigFile',
    ]),
    'USER_REPLAYER_WORKFLOW_OPTIONS': new Set([
        'jvmArgs',
        'loggingConfigurationOverrideConfigMap',
        'podReplicas',
        'resources',
    ]),
    'USER_REPLAYER_PROCESS_OPTIONS': new Set([
        'lookaheadTimeSeconds',
        'maxConcurrentRequests',
        'numClientThreads',
        'observedPacketConnectionTimeout',
        'otelCollectorEndpoint',
        'quiescentPeriodMs',
        'speedupFactor',
        'targetServerResponseTimeoutSeconds',
        'userAgent',
    ]),
    'USER_RFS_WORKFLOW_OPTIONS': new Set([
        'jvmArgs',
        'loggingConfigurationOverrideConfigMap',
        'podReplicas',
        'skipApproval',
        'useTargetClusterForWorkCoordination',
        'resources',
    ]),
    'USER_RFS_PROCESS_OPTIONS': new Set([
        'documentsPerBulkRequest',
        'documentsSizePerBulkRequest',
        'otelCollectorEndpoint',
        'serverGeneratedIds',
        'allowedDocExceptionTypes',
        'coordinatorRetryMaxRetries',
        'coordinatorRetryInitialDelayMs',
        'coordinatorRetryMaxDelayMs',
    ]),
};

const SCHEMAS: Record<string, z.ZodObject<any>> = {
    USER_PROXY_WORKFLOW_OPTIONS,
    USER_PROXY_PROCESS_OPTIONS,
    USER_REPLAYER_WORKFLOW_OPTIONS,
    USER_REPLAYER_PROCESS_OPTIONS,
    USER_RFS_WORKFLOW_OPTIONS,
    USER_RFS_PROCESS_OPTIONS,
};

describe('annotation completeness guard', () => {
    for (const [name, schema] of Object.entries(SCHEMAS)) {
        const allowlist = INTENTIONALLY_UNANNOTATED[name] ?? new Set();

        it(`${name}: every field is annotated or explicitly allowlisted`, () => {
            const missing: string[] = [];
            for (const [key, fieldSchema] of Object.entries(schema.shape)) {
                const meta = (fieldSchema as z.ZodType).meta() as FieldMeta | undefined;
                const hasAnnotation = meta?.changeRestriction || (meta?.checksumFor && meta.checksumFor.length > 0);
                if (!hasAnnotation && !allowlist.has(key)) {
                    missing.push(key);
                }
            }
            expect(missing).toEqual([]);
        });

        it(`${name}: allowlist has no stale entries`, () => {
            const stale: string[] = [];
            for (const key of allowlist) {
                if (!(key in schema.shape)) {
                    stale.push(key);
                }
            }
            expect(stale).toEqual([]);
        });
    }
});
