import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {BaseExpression, expr} from "@/schemas/expression";
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG, UNKNOWN} from "@/workflowTemplates/userSchemas";
import {PlainObject} from "@/schemas/plainObject";

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
        .addExpressionOutput("configContents", inputs=>expr.concat(
            conditionalInclude("kafka", expr.jsonToString(inputs.kafkaInfo)),
            conditionalInclude("source_cluster", expr.jsonToString(inputs.sourceCluster)),
            conditionalInclude("snapshot", expr.jsonToString(inputs.snapshotInfo)),
            conditionalInclude("target_cluster", expr.jsonToString(inputs.targetCluster)),
        ))
    )

    // .addTemplate("runMigrationCommand", b=>b
    //     .add
    //
    // )


    .getFullScope();