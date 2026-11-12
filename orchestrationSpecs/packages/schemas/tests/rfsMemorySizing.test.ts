import {USER_RFS_OPTIONS} from "../src/userSchemas";

const MIB = 1024 * 1024;
const base = {
    maxShardSizeBytes: 1024,
    disableFailedDocumentStream: true,
};

describe("RFS automatic memory sizing", () => {
    test("leaves the default heap policy alone without a source declaration", () => {
        const parsed = USER_RFS_OPTIONS.parse(base);

        expect(parsed.jvmArgs).toBe("");
        expect(parsed.resources.requests.memory).toBe("7000Mi");
        expect(parsed.resources.limits.memory).toBe("7000Mi");
    });

    test("sizes source-only memory from the Lucene read parallelism", () => {
        const parsed = USER_RFS_OPTIONS.parse({
            ...base,
            maxSourceDocumentSizeBytes: MIB,
        });

        expect(parsed.jvmArgs).toBe("-Xmx6400m");
        expect(parsed.resources.requests.memory).toBe("8000Mi");
        expect(parsed.resources.limits.memory).toBe("8000Mi");
    });

    test("adds transform output at CPU-bounded transform parallelism", () => {
        const parsed = USER_RFS_OPTIONS.parse({
            ...base,
            docTransformerConfig: '{"test":{}}',
            maxSourceDocumentSizeBytes: MIB,
            maxTransformOutputBytesPerInvocation: 10 * MIB,
            maxConnections: 10,
        });

        expect(parsed.jvmArgs).toBe("-Xmx6720m");
        expect(parsed.resources.requests.memory).toBe("8400Mi");
        expect(parsed.resources.limits.memory).toBe("8400Mi");
    });

    test("uses the CPU limit, preserves larger memory values, and replaces a user Xmx", () => {
        const parsed = USER_RFS_OPTIONS.parse({
            ...base,
            docTransformerConfig: '{"test":{}}',
            maxSourceDocumentSizeBytes: MIB,
            maxTransformOutputBytesPerInvocation: 10 * MIB,
            maxConnections: 10,
            jvmArgs: "-Xmx1g -XX:+UseG1GC",
            resources: {
                requests: {cpu: "1500m", memory: "10Gi"},
                limits: {cpu: "1500m", memory: "12Gi"},
            },
        });

        expect(parsed.jvmArgs).toBe("-XX:+UseG1GC -Xmx6560m");
        expect(parsed.resources.requests.memory).toBe("10Gi");
        expect(parsed.resources.limits.memory).toBe("12Gi");
    });

    test("requires both serialized-size declarations when a transform is configured", () => {
        const missingSource = USER_RFS_OPTIONS.safeParse({
            ...base,
            docTransformerConfig: '{"test":{}}',
            maxTransformOutputBytesPerInvocation: MIB,
        });
        const missingTransformOutput = USER_RFS_OPTIONS.safeParse({
            ...base,
            docTransformerConfig: '{"test":{}}',
            maxSourceDocumentSizeBytes: MIB,
        });
        const missingTransformer = USER_RFS_OPTIONS.safeParse({
            ...base,
            maxSourceDocumentSizeBytes: MIB,
            maxTransformOutputBytesPerInvocation: MIB,
        });

        expect(missingSource.success).toBe(false);
        expect(missingTransformOutput.success).toBe(false);
        expect(missingTransformer.success).toBe(false);
    });
});
