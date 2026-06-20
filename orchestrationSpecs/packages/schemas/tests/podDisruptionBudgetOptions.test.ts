import {
    USER_PROXY_OPTIONS,
    USER_REPLAYER_OPTIONS,
    USER_RFS_OPTIONS,
} from "../src/userSchemas";

describe("minPodReplicas validation", () => {
    test("defaults to zero for scalable services", () => {
        expect(USER_PROXY_OPTIONS.parse({listenPort: 9201}).minPodReplicas).toBe(0);
        expect(USER_REPLAYER_OPTIONS.parse({}).minPodReplicas).toBe(0);
        expect(USER_RFS_OPTIONS.parse({maxShardSizeBytes: 1024}).minPodReplicas).toBe(0);
    });

    test.each([
        ["proxy", USER_PROXY_OPTIONS, {listenPort: 9201, podReplicas: 2, minPodReplicas: 1}],
        ["replayer", USER_REPLAYER_OPTIONS, {podReplicas: 2, minPodReplicas: 1}],
        ["rfs", USER_RFS_OPTIONS, {podReplicas: 2, minPodReplicas: 1, maxShardSizeBytes: 1024}],
    ])("accepts %s minPodReplicas less than podReplicas", (_name, schema, data) => {
        expect(schema.safeParse(data).success).toBe(true);
    });

    test.each([
        ["proxy", USER_PROXY_OPTIONS, {listenPort: 9201, podReplicas: 1, minPodReplicas: 2}],
        ["replayer", USER_REPLAYER_OPTIONS, {podReplicas: 1, minPodReplicas: 2}],
        ["rfs", USER_RFS_OPTIONS, {podReplicas: 1, minPodReplicas: 2, maxShardSizeBytes: 1024}],
    ])("rejects %s minPodReplicas greater than podReplicas", (_name, schema, data) => {
        const result = schema.safeParse(data);
        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    path: ["minPodReplicas"],
                    message: expect.stringContaining("must be less than or equal to podReplicas"),
                })
            ]));
        }
    });

    test.each([
        ["negative", {podReplicas: 1, minPodReplicas: -1}],
        ["fractional", {podReplicas: 1, minPodReplicas: 0.5}],
    ])("rejects %s minPodReplicas", (_name, data) => {
        expect(USER_REPLAYER_OPTIONS.safeParse(data).success).toBe(false);
    });
});
