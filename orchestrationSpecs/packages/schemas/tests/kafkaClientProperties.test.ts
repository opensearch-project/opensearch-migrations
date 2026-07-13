import {KAFKA_CLIENT_PROPERTIES_CONFIG} from "../src/userSchemas";

describe("Kafka client properties schema", () => {
    it("accepts flat scalar producer and consumer properties", () => {
        expect(KAFKA_CLIENT_PROPERTIES_CONFIG.parse({
            producer: {
                "max.request.size": 8388608,
                "compression.type": "lz4",
            },
            consumer: {
                "fetch.max.bytes": 8388608,
                "allow.auto.create.topics": false,
            },
        })).toEqual({
            producer: {
                "max.request.size": 8388608,
                "compression.type": "lz4",
            },
            consumer: {
                "fetch.max.bytes": 8388608,
                "allow.auto.create.topics": false,
            },
        });
    });

    it("rejects nested values, arrays, and empty property names", () => {
        expect(KAFKA_CLIENT_PROPERTIES_CONFIG.safeParse({
            producer: {"max.request.size": {value: 8388608}},
        }).success).toBe(false);
        expect(KAFKA_CLIENT_PROPERTIES_CONFIG.safeParse({
            producer: {"compression.type": ["lz4"]},
        }).success).toBe(false);
        expect(KAFKA_CLIENT_PROPERTIES_CONFIG.safeParse({
            consumer: {"": "bad"},
        }).success).toBe(false);
    });
});
