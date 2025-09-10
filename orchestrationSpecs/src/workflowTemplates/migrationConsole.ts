import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {BaseExpression, expr, stringToRecord, toBase64} from "@/schemas/expression";
import {
    CLUSTER_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    SNAPSHOT_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG,
    UNKNOWN
} from "@/workflowTemplates/userSchemas";

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

export const MigrationConsole = WorkflowBuilder.create({
    k8sResourceName: "MigrationConsole",
    serviceAccountName: "argo-workflow-executor"
})

    // .addParams(CommonWorkflowParameters)


    .addTemplate("getConsoleConfig", b=>b
        .addRequiredInput("kafkaInfo", typeToken<z.infer<typeof UNKNOWN>>())
        .addRequiredInput("sourceCluster", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotInfo", typeToken<z.infer<typeof UNKNOWN>>())
        .addRequiredInput("targetCluster", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addSteps(s=>s)
        .addExpressionOutput("configContents", inputs=>
            stringToRecord(typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>(),
                expr.concat(
                    conditionalInclude("kafka", expr.recordToString(inputs.kafkaInfo)),
                    conditionalInclude("source_cluster", expr.recordToString(inputs.sourceCluster)),
                    conditionalInclude("snapshot", expr.recordToString(inputs.snapshotInfo)),
                    conditionalInclude("target_cluster", expr.recordToString(inputs.targetCluster)),
                ))
        )
        .addExpressionOutput("configContent2s", inputs=>"")
    )

    .addTemplate("runMigrationCommand", t=>t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(c=>c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addArgs(expr.toArray(
                expr.fillTemplate(SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE,
                    { "FILE_CONTENTS": expr.toBase64(c.inputs.configContents),
                        "COMMAND": c.inputs.command
                    })
                )
            )
        )
    )


    .getFullScope();