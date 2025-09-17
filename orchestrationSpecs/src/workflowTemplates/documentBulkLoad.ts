import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys,
    setupLog4jConfigForContainer, setupTestCredsForContainer, TargetClusterParameters
} from "@/workflowTemplates/commonWorkflowTemplates";
import {InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {asString, BaseExpression, expr} from "@/schemas/expression";
import {
    CONSOLE_SERVICES_CONFIG_FILE, RFS_OPTIONS, COMPLETE_SNAPSHOT_CONFIG,
    TARGET_CLUSTER_CONFIG
} from "@/workflowTemplates/userSchemas";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {INTERNAL} from "@/schemas/taskBuilder";
import {inputsToEnvVars, inputsToEnvVarsList, transformZodObjectToParams} from "@/utils";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {InputParamsToExpressions, ExtendScope} from "@/schemas/workflowTypes";
import {MISSING_FIELD} from "@/schemas/plainObject";
import {selectInputsFieldsAsExpressionRecord, selectInputsForRegister} from "@/schemas/parameterConversions";
import {typeToken} from "@/schemas/sharedTypes";

function getCheckRfsCompletionScript(sessionName: BaseExpression<string>) {
    const template = `
set -x && 
python -c '
import sys
from lib.console_link.console_link.environment import Environment
from lib.console_link.console_link.models.backfill_rfs import get_detailed_status_dict
from lib.console_link.console_link.models.backfill_rfs import all_shards_finished_processing

status = get_detailed_status_dict(Environment("/config/migration_services.yaml").target_cluster, 
                                  "{{SESSION_NAME}}")
print(status)
all_finished = all_shards_finished_processing(Environment("/config/migration_services.yaml").target_cluster,
                                              "{{SESSION_NAME}}")
sys.exit(0 if all_finished else 1)`;
    return expr.fillTemplate(template, {"SESSION_NAME": sessionName});
}

function getRfsReplicasetManifest
(args: {
    workflowName: BaseExpression<string>,
    sessionName: BaseExpression<string>,
    numPods: BaseExpression<number>,

    useLocalstackAwsCreds: BaseExpression<boolean>
    loggingConfigMap: BaseExpression<string>

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    inputsAsEnvList: Record<string, any>[],
})
{
    const baseContainerDefinition = {
        name: "bulk-loader",
        image: args.rfsImageName,
        imagePullPolicy: args.rfsImagePullPolicy,
        env: [
            ...args.inputsAsEnvList,
            {name: "LUCENE_DIR", value: expr.literal("/tmp") }
        ]
    };
    const finalContainerDefinition =
        setupTestCredsForContainer(args.useLocalstackAwsCreds,
            setupLog4jConfigForContainer(args.loggingConfigMap, baseContainerDefinition));
    return {
        apiVersion: "apps/v1",
            kind: "ReplicaSet",
        metadata: {
            name: expr.concat(args.sessionName, expr.literal("-reindex-from-snapshot")),
            labels: {
                "workflows.argoproj.io/workflow": args.workflowName
            },
        },
        spec: {
            replicas: args.numPods,
                selector: {
                matchLabels: {
                    app: "bulk-loader",
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "bulk-loader",
                        "workflows.argoproj.io/workflow": args.workflowName,
                    },
                },
                spec: {
                    containers: [finalContainerDefinition]
                },
            },
        }
    }
}

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "DocumentBulkLoad",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deleteReplicaSet", t=>t
        .addRequiredInput("name", typeToken<string>())
        .addResourceTask(b=>b
            .setDefinition({action: "delete", flags: ["--ignore-not-found"],
                 manifest: {
                    "apiVersion": "apps/v1",
                    "kind": "ReplicaSet",
                    "metadata": {
                        "name": expr.concat(expr.literal("bulk-loader-"), b.inputs.name)
                    }
                }
            })
        ))


    .addTemplate("waitForCompletion", t=>t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b=>b
            .addStep("checkRfsCompletion", MigrationConsole, "runMigrationCommand", c =>
            c.register({
                ...selectInputsForRegister(b, c),
                command: getCheckRfsCompletionScript(b.inputs.sessionName)
            }))
        )
        .addRetryParameters({
            limit: "200",
            retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", maxDuration: "300"}
        })
    )


    .addTemplate("createReplicaset", t=>t
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("numPods", c=>1)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")

        .addRequiredInput("snapshotName", typeToken<string>())
        .addRequiredInput("snapshotRepoPath", typeToken<string>())
        .addRequiredInput("s3Endpoint", typeToken<string>())
        .addRequiredInput("s3Region", typeToken<string>())

        .addInputsFromRecord(TargetClusterParameters)
        .addInputsFromRecord(transformZodObjectToParams(RFS_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b=>b
        .setDefinition({
            action: "create",
            setOwnerReference: true,
            manifest: getRfsReplicasetManifest({
                numPods: b.inputs.numPods,
                loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                useLocalstackAwsCreds: b.inputs.useLocalStack,
                sessionName: b.inputs.sessionName,
                rfsImageName: b.inputs.imageReindexFromSnapshotLocation,
                rfsImagePullPolicy: b.inputs.imageReindexFromSnapshotPullPolicy,
                inputsAsEnvList: [
                    ...inputsToEnvVarsList({...b.inputs})
                ],
                workflowName: expr.getWorkflowValue("name")
            })
        }))
    )


    .addTemplate("createReplicasetFromConfig", t=>t
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("numPods", c=>1)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")

        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())

        .addOptionalInput("backfillConfig", c=> ({} as z.infer<typeof RFS_OPTIONS>))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b=>b
            .addStep("createReplicaset", INTERNAL, "createReplicaset", c=>
            c.register({
                ...selectInputsForRegister(b, c),
                ...selectInputsFieldsAsExpressionRecord(b.inputs.backfillConfig, c),

                targetAwsRegion:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "authConfig", "region"), ""),
                targetAwsSigningName:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "authConfig", "service"), ""),
                targetCACert:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "authConfig", "caCert"), ""),
                targetClientSecretName:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "authConfig", "clientSecretName"), ""),
                targetInsecure:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "allow_insecure"), false),
                targetUsername:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "authConfig", "username"), ""),
                targetPassword:
                    expr.nullCoalesce(expr.jsonPathLoose(b.inputs.targetConfig, "authConfig", "password"), ""),

                s3Endpoint:       expr.jsonPathLoose(b.inputs.snapshotConfig, "repoConfig", "endpoint"),
                s3Region:         expr.jsonPathLoose(b.inputs.snapshotConfig, "repoConfig", "aws_region"),
                snapshotRepoPath: expr.jsonPathLoose(b.inputs.snapshotConfig, "repoConfig", "repoPath"),
                snapshotName:     expr.jsonPathLoose(b.inputs.snapshotConfig, "snapshotName"),
            })))
    )

    .addTemplate("runBulkLoad", t=>t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addOptionalInput("indices", c=>[] as readonly string[])
        .addOptionalInput("backfillConfig", c=> ({} as z.infer<typeof RFS_OPTIONS>))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot","MigrationConsole"]))

        .addSteps(b=>b
            .addStep("createReplicasetFromConfig", INTERNAL, "createReplicasetFromConfig", c=>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
            .addStep("setupWaitForCompletion", MigrationConsole, "getConsoleConfig", c=>
                c.register({
                    ...selectInputsForRegister(b,c),
                    kafkaInfo: MISSING_FIELD,
                    sourceConfig: MISSING_FIELD
                }))
            .addStep("waitForCompletion", INTERNAL, "waitForCompletion", c=>
                c.register({
                    ...selectInputsForRegister(b,c),
                    configContents: c.steps.setupWaitForCompletion.outputs.configContents
                }))
            .addStep("deleteReplicaSet", INTERNAL, "deleteReplicaSet", c=>
                c.register({name: b.inputs.sessionName}))
        )
    )

    .getFullScope();