import {USER_REPLAYER_OPTIONS} from "../src";

describe("replayer process configuration", () => {
    test("uses the deployed defaults without restoring retired workflow defaults", () => {
        const parsed = USER_REPLAYER_OPTIONS.parse({});

        expect(parsed.speedupFactor).toBe(1.1);
        expect(parsed.cancellationGraceMs).toBe(1000);
        expect(parsed.heartbeatExpirationIntervalSeconds).toBe(30);
        expect(parsed.maximumBackwardSkewSeconds).toBe(5);
        expect(parsed.sourceResponseRetryWindowSeconds).toBe(5);
        expect(parsed.readyRequestsBufferPerThread).toBe(2);
        expect(parsed.lookaheadTimeSeconds).toBeUndefined();
        expect(parsed.quiescentPeriodMs).toBeUndefined();
        expect(parsed.observedPacketConnectionTimeout).toBeUndefined();
        expect(parsed.numClientThreads).toBeUndefined();
    });

    test("accepts every deprecated workflow field without changing the new controls", () => {
        const parsed = USER_REPLAYER_OPTIONS.parse({
            lookaheadTimeSeconds: 401,
            quiescentPeriodMs: 5001,
            observedPacketConnectionTimeout: 361,
            maxConcurrentRequests: 17,
        });

        expect(parsed.lookaheadTimeSeconds).toBe(401);
        expect(parsed.quiescentPeriodMs).toBe(5001);
        expect(parsed.observedPacketConnectionTimeout).toBe(361);
        expect(parsed.maxConcurrentRequests).toBe(17);
        expect(parsed.heartbeatExpirationIntervalSeconds).toBe(30);
        expect(parsed.maximumBackwardSkewSeconds).toBe(5);
        expect(parsed.sourceResponseRetryWindowSeconds).toBe(5);
        expect(parsed.readyRequestsBufferPerThread).toBe(2);
    });

    test("rejects invalid startup bounds and conflicting target-attempt aliases", () => {
        for (const invalid of [
            {maxConcurrentTargetAttempts: 0},
            {maxConcurrentRequests: 0},
            {numClientThreads: 0},
            {cancellationGraceMs: -1},
            {heartbeatExpirationIntervalSeconds: 0},
            {maximumBackwardSkewSeconds: -1},
            {sourceResponseRetryWindowSeconds: 0},
            {readyRequestsBufferPerThread: 0},
            {speedupFactor: 0},
        ]) {
            expect(USER_REPLAYER_OPTIONS.safeParse(invalid).success).toBe(false);
        }

        expect(USER_REPLAYER_OPTIONS.safeParse({
            maxConcurrentTargetAttempts: 7,
            maxConcurrentRequests: 8,
        }).success).toBe(false);
        expect(USER_REPLAYER_OPTIONS.safeParse({
            maxConcurrentTargetAttempts: 7,
            maxConcurrentRequests: 7,
        }).success).toBe(true);
    });
});
