import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys,
    setupLog4jConfigForContainer, setupTestCredsForContainer
} from "@/workflowTemplates/commonWorkflowTemplates";
import {InputParamDef, InputParametersRecord, typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {asString, BaseExpression, expr} from "@/schemas/expression";
import {
    CONSOLE_SERVICES_CONFIG_FILE, RFS_OPTIONS, S3_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG,
    UNKNOWN
} from "@/workflowTemplates/userSchemas";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {INTERNAL, selectInputsForRegister} from "@/schemas/taskBuilder";
import {inputsToEnvVars, inputsToEnvVarsList, transformZodObjectToParams} from "@/utils";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {InputParamsToExpressions, ExtendScope} from "@/schemas/workflowTypes";

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
            replicas: "{{POD_REPLICA_COUNT}}",
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
        ),
        {limit: "200", retryPolicy: "Always", backoff: {duration: "5", factor: "2", maxDuration: "300"}}
    )

    .addTemplate("createReplicaset", t=>t
        .addOptionalInput("numPods", c=>1)
        .addOptionalInput("useLocalStack", c=>false)

        .addRequiredInput("targetAwsRegion", typeToken<string>())
        .addRequiredInput("targetAwsSigningName", typeToken<string>())
        .addRequiredInput("targetCACert", typeToken<string>())
        .addRequiredInput("targetClientSecretName", typeToken<string>()) // TODO
        .addRequiredInput("targetInsecure", typeToken<boolean>())
        .addRequiredInput("targetUsername", typeToken<string>())
        .addRequiredInput("targetPassword", typeToken<string>())

        .addRequiredInput("s3Endpoint", typeToken<string>())
        .addRequiredInput("s3Region", typeToken<string>())
        .addRequiredInput("s3RepoUri", typeToken<string>())

        .addInputsFromRecord(transformZodObjectToParams(RFS_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b=>b
        .setDefinition({
            action: "create",
            setOwnerReference: true,
            manifest: getRfsReplicasetManifest({
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
        .addRequiredInput("s3Config", typeToken<z.infer<typeof S3_CONFIG>>())
        .addOptionalInput("numPods", c=>1)
        .addOptionalInput("useLocalStack", c=>false)
        .addRequiredInput("target", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(transformZodObjectToParams(RFS_OPTIONS))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b=>b
            .addStep("createReplicaset", INTERNAL, "createReplicaset", c=>
            c.register({
                ...selectInputsForRegister(b, c),
                targetAwsRegion: expr.selectField(b.inputs.target, "authConfig[?(@.caCert)].caCert"),
                targetAwsSigningName: expr.selectField(b.inputs.target, "authConfig.region"),
                targetCACert: "",
                targetClientSecretName: "",
                targetInsecure: false,
                targetUsername: "",
                targetPassword: "",
                s3Endpoint: "",
                s3Region: "",
                s3RepoUri: ""
            })))
    )


    .addTemplate("runBulkLoadFromConfig", t=>t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(sb=>sb)
    )

    .getFullScope();