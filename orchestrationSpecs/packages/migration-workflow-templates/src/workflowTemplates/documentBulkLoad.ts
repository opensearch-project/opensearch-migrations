import {z} from "zod";
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    NAMED_TARGET_CLUSTER_CONFIG,
    ResourceRequirementsType,
    RFS_OPTIONS
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";

import {
    AllowLiteralOrExpression,
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    ReplicaSet
} from "@opensearch-migrations/argo-workflow-builders";
import {makeRepoParamDict} from "./metadataMigration";
import {
    setupLog4jConfigForContainer,
    setupTestCredsForContainer
} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeTargetParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getTargetHttpAuthCreds} from "./commonUtils/basicCredsGetters";

function makeParamsDict(
    sourceVersion: BaseExpression<z.infer<typeof CLUSTER_VERSION_STRING>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof RFS_OPTIONS>>>,
    sessionName: BaseExpression<string>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeTargetParamDict(targetConfig),
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "podReplicas", "resources")
        ),
        expr.mergeDicts(
            expr.makeDict({
                snapshotName: expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                sourceVersion: sourceVersion,
                sessionName: sessionName,
                luceneDir: "/tmp",
                cleanLocalDirs: true
            }),
            makeRepoParamDict(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), true)
        )
    );
}

function getRfsReplicasetManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>
    sessionName: BaseExpression<string>,
    podReplicas: BaseExpression<number>,
    basicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,

    useLocalstackAwsCreds: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>
}): ReplicaSet {
    const basicCredsSecretName = expr.ternary(
        expr.isEmpty(args.basicCredsSecretNameOrEmpty),
        expr.literal("empty"),
        args.basicCredsSecretNameOrEmpty
    );
    const useCustomLogging = expr.not(expr.isEmpty(args.loggingConfigMap));
    const baseContainerDefinition = {
        name: "bulk-loader",
        image: makeStringTypeProxy(args.rfsImageName),
        imagePullPolicy: makeStringTypeProxy(args.rfsImagePullPolicy),
        command: ["/rfs-app/runJavaWithClasspathWithRepeat.sh"],
        env: [
            // see getTargetHttpAuthCreds() - it's very similar, but for a raw K8s container, we pass
            // environment variables as a list, as K8s expects them.  The getTargetHttpAuthCreds()
            // returns them in a key-value format that the ContainerBuilder uses, which is converted
            // by the argoResourceRenderer.  It would be a nice idea to unify this format with the
            // container builder's, but it's probably a much bigger lift than it seems since we're
            // type checking this object against the k8s schema below.
            //
            // I could also use getTargetHttpAuthCreds to create the partial values, then substitute
            // those into here by splicing.  Writing a generic splicer isn't that straightforward since
            // there are a few other inconsistencies between the manifest and argo-container definitions.
            // As of now, we only have this block (though a couple others will come about too) and it
            // doesn't seem like it's worth the complexity.  There's some readability value to having
            // less normalization here as it benefits readability.
            {
                name: "TARGET_USERNAME",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(basicCredsSecretName),
                        key: "username",
                        optional: true
                    }
                }
            },
            {
                name: "TARGET_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(basicCredsSecretName),
                        key: "password",
                        optional: true
                    }
                }
            },
            // We don't have a mechanism to scrape these off disk so need to disable this to avoid filling up the disk
            {
                name: "FAILED_REQUESTS_LOGGER_LEVEL",
                value: "OFF"
            }
        ],
        args: [
            "org.opensearch.migrations.RfsMigrateDocuments",
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        resources: makeDirectTypeProxy(args.resources)
    };

    const finalContainerDefinition= setupTestCredsForContainer(
        args.useLocalstackAwsCreds,
        setupLog4jConfigForContainer(
            useCustomLogging,
            args.loggingConfigMap,
            { container: baseContainerDefinition, volumes: []}
        )
    );
    return {
        apiVersion: "apps/v1",
        kind: "ReplicaSet",
        metadata: {
            name: makeStringTypeProxy(expr.concat(args.sessionName, expr.literal("-reindex-from-snapshot"))),
            labels: {
                "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName)
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            selector: {
                matchLabels: {
                    app: "bulk-loader",
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "bulk-loader",
                        "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                    },
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    containers: [finalContainerDefinition.container],
                    volumes: [...finalContainerDefinition.volumes]
                }
            }
        }
    } as ReplicaSet;
}


function getCheckRfsCompletionScript(sessionName: BaseExpression<string>) {
    const template = `
set -e && 
python -c '
import sys
from lib.console_link.console_link.environment import Environment
from lib.console_link.console_link.models.backfill_rfs import get_detailed_status_obj
from lib.console_link.console_link.models.backfill_rfs import all_shards_finished_processing

status = get_detailed_status_obj(Environment(config_file="/config/migration_services.yaml").target_cluster,
                                 True,
                                 "{{SESSION_NAME}}")
print(status)
all_finished = all_shards_finished_processing(Environment(config_file="/config/migration_services.yaml").target_cluster,
                                              "{{SESSION_NAME}}")
sys.exit(0 if all_finished else 1)'`;
    return expr.fillTemplate(template, {"SESSION_NAME": sessionName});
}

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "document-bulk-load",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deleteReplicaSet", t => t
        .addRequiredInput("name", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete", flags: ["--ignore-not-found"],
                manifest: {
                    "apiVersion": "apps/v1",
                    "kind": "ReplicaSet",
                    "metadata": {
                        "name": expr.concat(expr.literal("bulk-loader-"), b.inputs.name)
                    }
                }
            })
        ))


    .addTemplate("waitForCompletion", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkRfsCompletion", MigrationConsole, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: getCheckRfsCompletionScript(b.inputs.sessionName)
                }))
        )
        .addRetryParameters({
            limit: "200",
            retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", cap: "300"}
        })
    )


    .addTemplate("createReplicaset", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("rfsJsonConfig", typeToken<string>())
        .addRequiredInput("basicCredsSecretNameOrEmpty", typeToken<string>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getRfsReplicasetManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    useLocalstackAwsCreds: expr.deserializeRecord(b.inputs.useLocalStack),
                    sessionName: b.inputs.sessionName,
                    basicCredsSecretNameOrEmpty: b.inputs.basicCredsSecretNameOrEmpty,
                    rfsImageName: b.inputs.imageReindexFromSnapshotLocation,
                    rfsImagePullPolicy: b.inputs.imageReindexFromSnapshotPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.rfsJsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                })
            }))
    )


    .addTemplate("createReplicasetFromConfig", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())

        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b => b
            .addStep("createReplicaset", INTERNAL, "createReplicaset", c =>
                c.register({
                    ...selectInputsForRegister(b,c),
                    podReplicas: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["podReplicas"], 1),
                    basicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.targetConfig),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["loggingConfigurationOverrideConfigMap"], ""),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    rfsJsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.sourceVersion,
                            b.inputs.targetConfig,
                            b.inputs.snapshotConfig,
                            b.inputs.documentBackfillConfig,
                            b.inputs.sessionName)
                    )),
                     resources: expr.serialize(expr.jsonPathStrict(b.inputs.documentBackfillConfig, "resources"))
                })
            )
        )
    )


    .addTemplate("runBulkLoad", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => b
            .addStep("createReplicasetFromConfig", INTERNAL, "createReplicasetFromConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
            .addStep("setupWaitForCompletion", MigrationConsole, "getConsoleConfig", c =>
                c.register(selectInputsForRegister(b, c)))
            .addStep("waitForCompletion", INTERNAL, "waitForCompletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.setupWaitForCompletion.outputs.configContents
                }))
            .addStep("deleteReplicaSet", INTERNAL, "deleteReplicaSet", c =>
                c.register({name: b.inputs.sessionName}))
        )
    )

    .getFullScope();
