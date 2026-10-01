import {RESOLVED_BACKFILL_REPEAT_POLICY} from "@opensearch-migrations/schemas";
import {z} from "zod";

const DECISION = RESOLVED_BACKFILL_REPEAT_POLICY.extend({
    run: z.number().int().positive(),
    snapshotStartTimeMillis: z.number().int().positive(),
    backfillCompletedAt: z.iso.datetime({offset: true}),
    snapshotLagSeconds: z.number().nonnegative(),
    action: z.enum(["continue", "stop", "fail"]),
    reason: z.enum(["anotherRunNeeded", "runLimitReached", "lagTargetMet", "lagTargetNotMet"]),
});
type Policy = z.infer<typeof RESOLVED_BACKFILL_REPEAT_POLICY>;
type Decision = z.infer<typeof DECISION>;

function outcome(policy: Policy, run: number, lagSeconds: number): Pick<Decision, "action" | "reason"> {
    if (policy.snapshotLagTargetSeconds !== undefined && lagSeconds <= policy.snapshotLagTargetSeconds) {
        return {action: "stop", reason: "lagTargetMet"};
    }
    if (run < policy.maxRuns) return {action: "continue", reason: "anotherRunNeeded"};
    return policy.snapshotLagTargetSeconds === undefined
        ? {action: "stop", reason: "runLimitReached"}
        : {action: "fail", reason: "lagTargetNotMet"};
}

/** Evaluate recorded times, never the current clock: approval waits and restarts do not change lag. */
export function evaluateBackfillRepeat(
    policy: Policy, run: number, snapshotStartTimeMillis: number, backfillCompletedAt: string,
    previous?: Decision,
): Decision {
    RESOLVED_BACKFILL_REPEAT_POLICY.parse(policy);
    z.number().int().min(1).max(policy.maxRuns).parse(run);
    z.number().int().positive().parse(snapshotStartTimeMillis);
    z.iso.datetime({offset: true}).parse(backfillCompletedAt);
    const snapshotLagSeconds = (Date.parse(backfillCompletedAt) - snapshotStartTimeMillis) / 1000;
    if (!Number.isFinite(snapshotLagSeconds) || snapshotLagSeconds < 0) {
        throw new Error("Snapshot start time must precede the recorded backfill completion time");
    }
    if (previous && (previous.maxRuns > policy.maxRuns ||
        previous.snapshotLagTargetSeconds !== policy.snapshotLagTargetSeconds)) {
        throw new Error("A started backfill policy only permits increasing maxRuns; reset before changing its stop condition");
    }
    if (previous && previous.run !== run) {
        throw new Error("Recorded backfill decision belongs to a different round");
    }
    // A monitor may refresh its completion observation on recovery. The first
    // persisted measurement remains authoritative for this migration resource.
    const measurement = previous ?? {snapshotStartTimeMillis, backfillCompletedAt, snapshotLagSeconds};
    return DECISION.parse({
        ...measurement, ...policy, run, ...outcome(policy, run, measurement.snapshotLagSeconds),
    });
}

const REQUEST = z.object({
    policy: RESOLVED_BACKFILL_REPEAT_POLICY,
    run: z.number().int().positive(),
    resourceUid: z.string().min(1),
    configChecksum: z.string().min(1),
    migration: z.object({
        metadata: z.object({uid: z.string(), resourceVersion: z.string()}),
        spec: z.object({dataSnapshotResourceName: z.string()}),
        status: z.object({
            configChecksum: z.string(),
            sequenceCompletionChecksum: z.string(),
            documentBackfill: z.object({
                phase: z.literal("Completed"),
                updatedAt: z.iso.datetime({offset: true}),
            }),
            backfillRepeat: DECISION.optional(),
        }),
    }),
    snapshot: z.object({
        metadata: z.object({name: z.string()}),
        status: z.object({
            phase: z.literal("Completed"),
            snapshotStartTimeMillis: z.number().int().positive(),
        }),
    }),
});

/** Return a conditional status patch so a reset or concurrent update cannot receive a stale decision. */
export function evaluateBackfillRepeatRequest(input: unknown) {
    const request = REQUEST.parse(input);
    const {migration, snapshot, policy, run} = request;
    if (migration.metadata.uid !== request.resourceUid ||
        migration.status.configChecksum !== request.configChecksum ||
        migration.status.sequenceCompletionChecksum !== request.configChecksum ||
        migration.spec.dataSnapshotResourceName !== snapshot.metadata.name) {
        throw new Error("Backfill policy inputs no longer match the completed and approved migration");
    }
    const decision = evaluateBackfillRepeat(
        policy, run, snapshot.status.snapshotStartTimeMillis, migration.status.documentBackfill.updatedAt,
        migration.status.backfillRepeat,
    );
    return {
        decision,
        patch: [
            {op: "test", path: "/metadata/uid", value: request.resourceUid},
            {op: "test", path: "/metadata/resourceVersion", value: migration.metadata.resourceVersion},
            {op: "add", path: "/status/backfillRepeat", value: decision},
        ],
    };
}

export async function main() {
    const chunks: Buffer[] = [];
    for await (const chunk of process.stdin) chunks.push(Buffer.from(chunk));
    process.stdout.write(JSON.stringify(evaluateBackfillRepeatRequest(JSON.parse(Buffer.concat(chunks).toString("utf8")))));
}
