import {
    DEFAULT_OTEL_METRICS_COLLECTOR_ENDPOINT,
    SOLR_CREATE_BACKUP_OPTIONS,
    USER_CREATE_SNAPSHOT_PROCESS_OPTIONS,
    USER_METADATA_PROCESS_OPTIONS,
    USER_PROXY_PROCESS_OPTIONS,
    USER_REPLAYER_PROCESS_OPTIONS,
    USER_RFS_PROCESS_OPTIONS,
} from "../src";

const schemas = [
    ["capture proxy", USER_PROXY_PROCESS_OPTIONS, {listenPort: 9200}],
    ["traffic replayer", USER_REPLAYER_PROCESS_OPTIONS, {}],
    ["create snapshot", USER_CREATE_SNAPSHOT_PROCESS_OPTIONS, {}],
    ["metadata migration", USER_METADATA_PROCESS_OPTIONS, {}],
    ["document backfill", USER_RFS_PROCESS_OPTIONS, {}],
    ["Solr backup", SOLR_CREATE_BACKUP_OPTIONS, {}],
] as const;

const DEFAULT_METRICS_ENDPOINT = DEFAULT_OTEL_METRICS_COLLECTOR_ENDPOINT;
const METRICS_ENDPOINT = "http://metrics:4317";
const TRACE_ENDPOINT = "http://traces:4317";

describe.each(schemas)("%s OpenTelemetry export options", (_name, schema, baseOptions) => {
    // [case,                         enabled,   endpoint,                 valid, expectedEnabled, expectedEndpoint]
    test.each([
        ["true with endpoint",          true,      METRICS_ENDPOINT,        true,  true,            METRICS_ENDPOINT],
        ["true without endpoint",       true,      undefined,               true,  true,            DEFAULT_METRICS_ENDPOINT],
        ["true with blank endpoint",    true,      "",                      false, undefined,       undefined],
        ["false with endpoint",         false,     METRICS_ENDPOINT,        false, undefined,       undefined],
        ["false without endpoint",      false,     undefined,               true,  false,           undefined],
        ["false with blank endpoint",   false,     "",                      false, undefined,       undefined],
        ["unset with endpoint",         undefined, METRICS_ENDPOINT,        true,  true,            METRICS_ENDPOINT],
        ["unset without endpoint",      undefined, undefined,               true,  true,            DEFAULT_METRICS_ENDPOINT],
        ["unset with blank endpoint",   undefined, "",                      false, undefined,       undefined],
    ] as const)("metrics: %s", (_case, enabled, endpoint, valid, expectedEnabled, expectedEndpoint) => {
        const result = schema.safeParse({
            ...baseOptions,
            ...(enabled === undefined ? {} : {otelMetricsExportEnabled: enabled}),
            ...(endpoint === undefined ? {} : {otelMetricsCollectorEndpoint: endpoint}),
        });

        expect(result.success).toBe(valid);
        if (result.success) {
            expect(result.data.otelMetricsExportEnabled).toBe(expectedEnabled);
            expect(result.data.otelMetricsCollectorEndpoint).toBe(expectedEndpoint);
        }
    });

    // [case,                    enabled,   endpoint,           valid, expectedEnabled, expectedEndpoint]
    test.each([
        ["true with endpoint",     true,      TRACE_ENDPOINT, true,  true,            TRACE_ENDPOINT],
        ["true without endpoint",  true,      undefined,      false, undefined,       undefined],
        ["false with endpoint",    false,     TRACE_ENDPOINT, false, undefined,       undefined],
        ["false without endpoint", false,     undefined,      true,  false,           undefined],
        ["unset with endpoint",    undefined, TRACE_ENDPOINT, true,  true,            TRACE_ENDPOINT],
        ["unset without endpoint", undefined, undefined,      true,  false,           undefined],
    ] as const)("traces: %s", (_case, enabled, endpoint, valid, expectedEnabled, expectedEndpoint) => {
        const result = schema.safeParse({
            ...baseOptions,
            ...(enabled === undefined ? {} : {otelTraceExportEnabled: enabled}),
            ...(endpoint === undefined ? {} : {otelTraceCollectorEndpoint: endpoint}),
        });

        expect(result.success).toBe(valid);
        if (result.success) {
            expect(result.data.otelTraceExportEnabled).toBe(expectedEnabled);
            expect(result.data.otelTraceCollectorEndpoint).toBe(expectedEndpoint);
        }
    });

    test("rejects a blank trace endpoint", () => {
        expect(schema.safeParse({
            ...baseOptions,
            otelTraceCollectorEndpoint: "   ",
        }).success).toBe(false);
    });
});
