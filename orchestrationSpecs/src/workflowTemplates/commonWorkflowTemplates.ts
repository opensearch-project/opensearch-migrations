import {
    defineParam,
    defineRequiredParam,
    InputParamDef,
    InputParametersRecord
} from "@/schemas/parameterSchemas";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {z} from "zod/index";
import {DYNAMIC_SNAPSHOT_CONFIG, COMPLETE_SNAPSHOT_CONFIG, TARGET_CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import {BaseExpression, expr} from "@/schemas/expression";
import {typeToken} from "@/schemas/sharedTypes";

export const CommonWorkflowParameters = {
    etcdEndpoints:        defineParam({ expression: "http://etcd.ma.svc.cluster.local:2379" }),
    etcdUser:             defineParam({ expression: "root" }),
    etcdPassword:         defineParam({ expression: "password" }),
    s3SnapshotConfigMap:  defineParam({ expression: "s3-snapshot-config" }),
    imageConfigMapName:   defineParam({ expression: "migration-image-config"})
} as const;

export const LogicalOciImages = [
    "CaptureProxy",
    "TrafficReplayer",
    "ReindexFromSnapshot",
    "MigrationConsole",
    "EtcdUtils",
] as const;
export type LogicalOciImagesKeys = typeof LogicalOciImages[number];

export function makeRequiredImageParametersForKeys<K extends LogicalOciImagesKeys, T extends readonly K[]>(keys: T) {
    return Object.fromEntries(
        keys.flatMap(k => [
            [`image${k}Location`, defineRequiredParam<string>()],
            [`image${k}PullPolicy`, defineRequiredParam<IMAGE_PULL_POLICY>()]
        ])
    ) as Record<`image${typeof keys[number]}Location`, InputParamDef<string,true>> &
        Record<`image${typeof keys[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY,true>>;
}
export const ImageParameters = makeRequiredImageParametersForKeys(LogicalOciImages);

export const TargetClusterParameters = {
    targetAwsRegion: defineRequiredParam<string>(),
    targetAwsSigningName: defineRequiredParam<string>(),
    targetCACert: defineRequiredParam<string>(),
    targetClientSecretName: defineRequiredParam<string>(), // TODO
    targetInsecure: defineRequiredParam<boolean>(),
    targetUsername: defineRequiredParam<string>(),
    targetPassword: defineRequiredParam<string>()
}

export function extractTargetKeysToExpressionMap(targetConfig: BaseExpression<z.infer<typeof TARGET_CLUSTER_CONFIG>>) {
    return {
        targetAwsRegion:        expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "authConfig", "region"), ""),
        targetAwsSigningName:   expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "authConfig", "service"), ""),
        targetCACert:           expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "authConfig", "caCert"), ""),
        targetClientSecretName: expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "authConfig", "clientSecretName"), ""),
        targetInsecure:         expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "allow_insecure"), false),
        targetUsername:         expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "authConfig", "username"), ""),
        targetPassword:         expr.nullCoalesce(expr.jsonPathLoose(targetConfig, "authConfig", "password"), ""),
    };
}

export const dynamicSnapshotConfigParam = {
    snapshotConfig: defineRequiredParam<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>({
        description: "Snapshot storage details (region, endpoint, etc)"})};

export const completeSnapshotConfigParam = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({
        description: "Snapshot storage details (region, endpoint, etc)"})};

export function safeSpread<T>(list: T[]) {
    return list === undefined ? [] : list;
}

export function setupTestCredsForContainer(
    useLocalStack: BaseExpression<boolean>,
    containerDef: Record<string, any>)
{
    const {volumeMounts, env, ...restOfContainer} = containerDef;
    return {
        ...restOfContainer,
        env: [
            ...safeSpread(env),
            { name: "AWS_SHARED_CREDENTIALS_FILE", value: expr.literal("/config/credentials") }
        ],
        volumeMounts: [
            ...safeSpread(volumeMounts),
            {
                name: expr.ternary(useLocalStack,
                    expr.literal("localstack-test-creds"),
                    expr.literal("empty-configuration")),
                mountPath: "/config/credentials",
                readOnly: true
            }
        ]
    }
}

export function setupLog4jConfigForContainer(
    loggingConfigMapName: BaseExpression<string>,
    containerDef: Record<string, any>)
{
    const {volumeMounts, env, ...restOfContainer} = containerDef;
    const configIsEmpty = expr.equals(expr.literal(""), loggingConfigMapName);
    return {
        ...restOfContainer,
        env: [
            ...safeSpread(env),
            {
                name: "LOGGING_CONFIGURATION",
                value: expr.ternary(configIsEmpty, expr.literal(""), expr.literal("/config/logConfiguration"))
            }
        ],
        volumeMounts: [
            ...safeSpread(volumeMounts),
            {
                name: expr.ternary(configIsEmpty,
                    expr.literal("empty-configuration"),
                    loggingConfigMapName), // it's fine for this to be mounted
                mountPath: "/config/logConfiguration",
                readOnly: true
            }
        ]
    }
}
