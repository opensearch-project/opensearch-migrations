import {
    AllowLiteralOrExpression,
    BaseExpression,
    configMapKey,
    defineParam,
    expr,
    FromParameterExpression,
    InputParamDef,
    PlainObject,
    Serialized, toExpression,
    TypeToken,
    WorkflowParameterSource
} from "@opensearch-migrations/argo-workflow-builders";
import {z} from "zod";
import {NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO, NAMED_TARGET_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";

export function getSourceTargetPath(
    sourceLabel: AllowLiteralOrExpression<string>,
    target: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>
) {
    return [
        toExpression(sourceLabel),
        expr.get(expr.deserializeRecord(target), "label")
    ];
}

export function getSourceTargetPathAndSnapshotIndex(
    sourceLabel: AllowLiteralOrExpression<string>,
    target: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotName: AllowLiteralOrExpression<string>
) {
    return [
        ...getSourceTargetPath(sourceLabel, target),
        toExpression(snapshotName)
    ];
}

export function getSourceTargetPathAndSnapshotAndMigrationIndex(
    sourceLabel: AllowLiteralOrExpression<string>,
    target: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotName: AllowLiteralOrExpression<string>,
    migrationName: AllowLiteralOrExpression<string>
) {
    return [
        ...getSourceTargetPathAndSnapshotIndex(sourceLabel, target, toExpression(snapshotName)),
        toExpression(migrationName)
    ];
}


export function getApprovalMap<T extends PlainObject & Partial<Record<string, boolean>>>(
    approvalConfigMapName: FromParameterExpression<string, WorkflowParameterSource>,
    tt: TypeToken<T>
)
{
    return {
        skipApprovalMap: defineParam({
            from: configMapKey(approvalConfigMapName, "autoApprove", true),
            type: tt,
            expression: {} as const as T
        })
    };
}

export function getApprovalsFromMap<
    K extends string,
    PATH extends string
>(
    map: BaseExpression<Record<string, boolean>>,
    paramsToBuild: Record<K, PATH>
): Record<K, InputParamDef<boolean, false>>
{
    const result = {} as Record<K, InputParamDef<boolean, false>>;
    for (const key in paramsToBuild) {
        const v: string = paramsToBuild[key];
        result[key] = defineParam({
            expression: expr.dig(map, [v], expr.literal(false))
        });
    }
    return result;
}