import {defineParam, defineRequiredParam, InputParamDef} from "@/schemas/parameterSchemas";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {z} from "zod";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    DYNAMIC_SNAPSHOT_CONFIG,
    TARGET_CLUSTER_CONFIG
} from "@/workflowTemplates/userSchemas";
import {BaseExpression, expr} from "@/schemas/expression";
import {Serialized} from "@/schemas/plainObject";

export const CommonWorkflowParameters = {
    etcdEndpoints: defineParam({expression: "http://etcd.ma.svc.cluster.local:2379"}),
    etcdUser: defineParam({expression: "root"}),
    etcdPassword: defineParam({expression: "password"}),
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"})
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
    ) as Record<`image${typeof keys[number]}Location`, InputParamDef<string, true>> &
        Record<`image${typeof keys[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, true>>;
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

export function extractTargetKeysToExpressionMap(targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return {
        targetAwsRegion:
            expr.dig(expr.deserializeRecord(targetConfig), "", "authConfig", "region"),
        targetAwsSigningName:
            expr.dig(expr.deserializeRecord(targetConfig), "", "authConfig", "service"),
        targetCACert:
            expr.dig(expr.deserializeRecord(targetConfig), "", "authConfig", "caCert"),
        targetClientSecretName:
            expr.dig(expr.deserializeRecord(targetConfig), "", "authConfig", "clientSecretName"),
        targetInsecure:
            expr.dig(expr.deserializeRecord(targetConfig), false, "allow_insecure"),
        targetUsername:
            expr.dig(expr.deserializeRecord(targetConfig), "", "authConfig", "username"),
        targetPassword:
            expr.dig(expr.deserializeRecord(targetConfig), "", "authConfig", "password"),
    };
}

export const dynamicSnapshotConfigParam = {
    snapshotConfig: defineRequiredParam<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>({
        description: "Snapshot storage details (region, endpoint, etc)"
    })
};

export const completeSnapshotConfigParam = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({
        description: "Snapshot storage details (region, endpoint, etc)"
    })
};

export function safeSpread<T>(list: T[]) {
    return list === undefined ? [] : list;
}

export function setupTestCredsForContainer(
    useLocalStack: BaseExpression<boolean>,
    containerDef: Record<string, any>) {
    const {volumeMounts, env, ...restOfContainer} = containerDef;
    return {
        ...restOfContainer,
        env: [
            ...safeSpread(env),
            {name: "AWS_SHARED_CREDENTIALS_FILE", value: expr.literal("/config/credentials")}
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
    containerDef: Record<string, any>) {
    const {volumeMounts, env, ...restOfContainer} = containerDef;
    const configIsEmpty = expr.equals(expr.literal(""), loggingConfigMapName);
    return {
        ...restOfContainer,
        env: [
            ...safeSpread(env),
            {
                name: "JAVA_OPTS",
                value: expr.ternary(configIsEmpty, expr.literal(""), expr.literal("-Dlog4j2.configurationFile=/config/logConfiguration"))
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
