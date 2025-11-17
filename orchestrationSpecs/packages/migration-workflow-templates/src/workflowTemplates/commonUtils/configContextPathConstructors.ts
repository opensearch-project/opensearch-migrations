import {BaseExpression, expr} from "@opensearch-migrations/argo-workflow-builders";
import {z} from "zod";
import {NAMED_SOURCE_CLUSTER_CONFIG, NAMED_TARGET_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";

function getSourceTargetPath(
    source: BaseExpression<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>,
    target: BaseExpression<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>
) {
    return expr.concat(
        expr.get(source, "name"),
        expr.literal(":"),
        expr.get(target, "name"),
    );
}

function getSourceTargetPathAndSnapshotIndex(
    source: BaseExpression<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>,
    target: BaseExpression<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>,
    snapshotIdx: BaseExpression<number>
) {
    return expr.concat(
        getSourceTargetPath(source, target),
        expr.literal(":"),
        expr.toString(snapshotIdx)
    );
}

function getSourceTargetPathAndSnapshotAndMigrationIndex(
    source: BaseExpression<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>,
    target: BaseExpression<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>,
    snapshotIdx: BaseExpression<number>,
    migrationIdx: BaseExpression<number>
) {
    return expr.concat(
        getSourceTargetPathAndSnapshotIndex(source, target, snapshotIdx),
        expr.literal(":"),
        expr.toString(migrationIdx)
    );
}