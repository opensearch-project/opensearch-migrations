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
import {NAMED_SOURCE_CLUSTER_CONFIG, NAMED_TARGET_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";

export function getSourceTargetPath(
    source: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    target: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>
) {
    return [
        expr.get(expr.deserializeRecord(source), "name"),
        expr.get(expr.deserializeRecord(target), "name")
    ];
}

export function getSourceTargetPathAndSnapshotIndex(
    source: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    target: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotName: AllowLiteralOrExpression<string>
) {
    return [
        ...getSourceTargetPath(source, target),
        toExpression(snapshotName)
    ];
}

export function getSourceTargetPathAndSnapshotAndMigrationIndex(
    source: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    target: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotName: AllowLiteralOrExpression<string>,
    migrationName: AllowLiteralOrExpression<string>
) {
    return [
        ...getSourceTargetPathAndSnapshotIndex(source, target, toExpression(snapshotName)),
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