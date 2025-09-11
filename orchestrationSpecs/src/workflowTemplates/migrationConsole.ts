import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {defineRequiredParam, typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {AllowLiteralOrExpression, BaseExpression, expr, stringToRecord, toBase64} from "@/schemas/expression";
import {
    CLUSTER_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE, S3_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG,
    UNKNOWN
} from "@/workflowTemplates/userSchemas";
import {INTERNAL, selectInputsForRegister} from "@/schemas/taskBuilder";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";

const SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE = `
set -e -x

# Save pod name to output path
echo $HOSTNAME > /tmp/podname

base64 -d > /config/migration_services.yaml << EOF
{{FILE_CONTENTS}}
EOF

. /etc/profile.d/venv.sh
source /.venv/bin/activate

echo file dump
echo ---
cat /config/migration_services.yaml
echo ---

{{COMMAND}}
`;

function getConsoleDeploymentResource(
    name: AllowLiteralOrExpression<string>,
    migrationConsoleImage: AllowLiteralOrExpression<string>,
    migrationConsolePullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>,
    base64ConfigContents: AllowLiteralOrExpression<string>,
    command: AllowLiteralOrExpression<string>,
) {
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": name
        },
        "spec": {
            "replicas": 1,
            "selector": {
                "matchLabels": {
                    "app": "user-environment"
                }
            },
            "template": {
                "metadata": {
                    "labels": {
                        "app": "user-environment"
                    }
                },
                "spec": {
                    "containers": [
                        {
                            "name": "main",
                            "image": migrationConsoleImage,
                            "imagePullPolicy": migrationConsolePullPolicy,
                            "command": [
                                "/bin/sh",
                                "-c",
                                "set -e -x\n\nbase64 -d > /config/migration_services.yaml << EOF\n" +
                                "" +
                                base64ConfigContents +
                                "EOF\n" +
                                "" +
                                ". /etc/profile.d/venv.sh\n" +
                                "source /.venv/bin/activate\n" +
                                "" +
                                "echo file dump\necho ---\n" +
                                "cat /config/migration_services.yaml\n" +
                                "echo ---\n" +
                                "" +
                                command
                            ]
                        }
                    ]
                }
            }
        }
    }
};

function conditionalInclude(label: string, contents: BaseExpression<string>): BaseExpression<string> {
    return expr.ternary(expr.equals(contents, expr.literal("")),
        expr.literal(""), // do-nothing branch
        expr.concat(
            // Fill out the appropriate line(s) of the config.  Notice that yaml allows inlining JSON,
            // which makes handling contents, especially at argo runtime, simpler
            expr.literal(label+": "),
            contents,
            expr.literal("\n")
        )
    );
}

const configComponentParameters = {
    command: defineRequiredParam<string>({
        description: "Command to run"}),
    kafkaInfo: defineRequiredParam<z.infer<typeof UNKNOWN>[]>({
        description: "Snapshot configuration information (JSON)"}),
    sourceCluster: defineRequiredParam<z.infer<typeof CLUSTER_CONFIG>[]>({
        description: "Source cluster configuration (JSON)"}),
    snapshotInfo: defineRequiredParam<z.infer<typeof UNKNOWN>[]>({
        description: "Snapshot configuration information (JSON)"}),
    targetCluster: defineRequiredParam<z.infer<typeof TARGET_CLUSTER_CONFIG>[]>({
        description: "Target cluster configuration (JSON)"})
};


export const MigrationConsole = WorkflowBuilder.create({
    k8sResourceName: "MigrationConsole",
    serviceAccountName: "argo-workflow-executor"
})

    // .addParams(CommonWorkflowParameters)


    .addTemplate("getConsoleConfig", b=>b
        .addInputsFromRecord(configComponentParameters)
        .addSteps(s=>s)
        .addExpressionOutput("configContents", c=>
            stringToRecord(typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>(),
                expr.concat(
                    conditionalInclude("kafka", expr.recordToString(c.inputs.kafkaInfo)),
                    conditionalInclude("source_cluster", expr.recordToString(c.inputs.sourceCluster)),
                    conditionalInclude("snapshot", expr.recordToString(c.inputs.snapshotInfo)),
                    conditionalInclude("target_cluster", expr.recordToString(c.inputs.targetCluster)),
                ))
        )
    )


    .addTemplate("runMigrationCommand", t=>t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(c=>c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addArgs([
                expr.fillTemplate(SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE,
                    { "FILE_CONTENTS": expr.toBase64(expr.recordToString(c.inputs.configContents)),
                        "COMMAND": c.inputs.command
                    })
                ]
            )
        )
    )


    .addTemplate("deployConsoleWithConfig", t=>t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("name", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addResourceTask(b=>b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                successCondition: "status.availableReplicas > 0",
                manifest: getConsoleDeploymentResource(b.inputs.name,
                    b.inputs.imageMigrationConsoleLocation,
                    b.inputs.imageMigrationConsolePullPolicy,
                    expr.toBase64(expr.recordToString(b.inputs.configContents)),
                    b.inputs.command)
            }))

        .addJsonPathOutput("deploymentName", "{.metadata.name}", typeToken<string>())
    )


    .addTemplate("runConsole", t=>t
        .addInputsFromRecord(configComponentParameters)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(s=>s
            .addStep("getConsoleConfig", INTERNAL, "getConsoleConfig", c=>
                c.register(selectInputsForRegister(s, c)))


            .addStep("runConsoleWithConfig", INTERNAL, "runMigrationCommand", c=>
                c.register({
                    ...selectInputsForRegister(s, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
    )


    .addTemplate("deployConsole", t=>t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addInputsFromRecord(configComponentParameters)
        .addRequiredInput("name", typeToken<string>())
        .addSteps(s=>s
            .addStep("getConsoleConfig", INTERNAL, "getConsoleConfig", c=>
                c.register(selectInputsForRegister(s, c)))
            .addStep("deployConsoleWithConfig", INTERNAL, "deployConsoleWithConfig", c=>
                c.register({
                    ...selectInputsForRegister(s, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
        .addExpressionOutput("deploymentName",
                c=>c.steps.getConsoleConfig.outputs.configContents)
    )

    .getFullScope();