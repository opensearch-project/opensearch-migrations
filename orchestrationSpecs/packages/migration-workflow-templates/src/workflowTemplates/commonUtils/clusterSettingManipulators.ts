import {BaseExpression, expr, Serialized} from "@opensearch-migrations/argo-workflow-builders";
import {CLUSTER_CONFIG, SOURCE_CLUSTER_CONFIG, TARGET_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";

function makeAuthDict(clusterType: string, targetConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>) {
    const safeAuthConfig = (expr.getLoose(expr.deserializeRecord(targetConfig), "authConfig"));
    return expr.ternary(
        expr.hasKey(expr.deserializeRecord(targetConfig), "authConfig"),
        expr.ternary(
            expr.hasKey(safeAuthConfig, "sigv4"),
            expr.makeDict({
                [`${clusterType}AwsServiceSigningName`]: expr.getLoose(expr.getLoose(safeAuthConfig, "sigv4"), "service"),
                [`${clusterType}AwsRegion`]: expr.getLoose(expr.getLoose(safeAuthConfig, "sigv4"), "region")
            }),
            expr.ternary(
                expr.hasKey(safeAuthConfig, "mtls"),
                expr.makeDict({
                    [`${clusterType}CaCert`]: expr.getLoose(expr.getLoose(safeAuthConfig, "mtls"), "caCert"),
                }),
                expr.literal({})
            )
        ),
        expr.literal({}));
}

export function getHttpAuthSecretName(clusterConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>> | BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return expr.dig(expr.deserializeRecord(clusterConfig as BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>), ["authConfig", "basic", "secretName"], "");
}

export function makeClusterParamDict(clusterType: string, clusterConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>) {
    const cc = expr.deserializeRecord(clusterConfig);
    return expr.mergeDicts(
        expr.mergeDicts(
            makeAuthDict(clusterType, clusterConfig),
            expr.ternary(
                expr.hasKey(cc, "endpoint"),
                expr.makeDict({
                    [`${clusterType}Host`]: expr.getLoose(cc, "endpoint")
                }),
                expr.literal({})
            )
        ),
        expr.makeDict({
            [`${clusterType}Insecure`]: expr.dig(expr.deserializeRecord(clusterConfig), ["allowInsecure"], false)
        })
    );
}

export function makeTargetParamDict(targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return makeClusterParamDict("target", targetConfig as BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>);
}

export function makeCoordinatorParamDict(coordinatorConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return makeClusterParamDict("coordinator", coordinatorConfig as BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>);
}

// The functions below are still used by the replaer, but they should probably be replaced with the ones above
// once we circle back to finalize replayer support
export function extractConnectionKeysToExpressionMap(
    clusterType: string,
    targetConfig: BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>
) {
    return {
        [`${clusterType}AwsRegion`]:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "sigv4", "region"], ""),
        [`${clusterType}AwsSigningName`]:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "sigv4", "service"], ""),
        [`${clusterType}CACert`]:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "mtls", "caCert"], ""),
        [`${clusterType}ClientSecretName`]:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "mtls", "clientSecretName"], ""),
        [`${clusterType}Insecure`]:
            expr.dig(expr.deserializeRecord(targetConfig), ["allowInsecure"], false),
    };
}

export function extractSourceKeysToExpressionMap(sourceConfig: BaseExpression<Serialized<z.infer<typeof SOURCE_CLUSTER_CONFIG>>>) {
    return extractConnectionKeysToExpressionMap("source", sourceConfig);
}

export function extractTargetKeysToExpressionMap(targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return extractConnectionKeysToExpressionMap("target", targetConfig as BaseExpression<Serialized<z.infer<typeof CLUSTER_CONFIG>>>);
}