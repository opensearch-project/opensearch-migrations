import {
    ARGO_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    KAFKA_CLUSTER_CONFIG,
    HTTP_AUTH_BASIC,
    HTTP_AUTH_SIGV4,
    HTTP_AUTH_MTLS,
    PROXY_TLS_CONFIG,
    SNAPSHOT_NAME_CONFIG,
    zodSchemaToJsonSchema
} from "../src";
import {z} from "zod";

describe('test schemas matches expected', () => {
    it("argo schema matches expected", () => {
        const schema = zodSchemaToJsonSchema(z.array(ARGO_MIGRATION_CONFIG))
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });

    it("argo user matches expected", () => {
        const schema = zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG)
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });

    it("union field schemas render as expected", () => {
        const unions = {
            KAFKA_CLUSTER_CONFIG: zodSchemaToJsonSchema(z.object({ kafkaCluster: KAFKA_CLUSTER_CONFIG })),
            HTTP_AUTH: zodSchemaToJsonSchema(z.object({ authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]) })),
            PROXY_TLS_CONFIG: zodSchemaToJsonSchema(z.object({ tls: PROXY_TLS_CONFIG })),
            SNAPSHOT_NAME_CONFIG: zodSchemaToJsonSchema(z.object({ snapshot: SNAPSHOT_NAME_CONFIG })),
        };
        expect(JSON.stringify(unions, null, 2)).toMatchSnapshot();
    });
});
