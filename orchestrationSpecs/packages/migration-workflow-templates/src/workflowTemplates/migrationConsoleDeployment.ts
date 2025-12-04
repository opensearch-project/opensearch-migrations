import {
    AllowLiteralOrExpression, expr,
    IMAGE_PULL_POLICY, INTERNAL, selectInputsForRegister, typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    CONSOLE_SERVICES_CONFIG_FILE,
    DEFAULT_RESOURCES,
    ResourceRequirementsType
} from "@opensearch-migrations/schemas";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {configComponentParameters, MigrationConsole} from "./migrationConsole";
import {z} from "zod";

function getConsoleDeploymentResource(
    name: AllowLiteralOrExpression<string>,
    migrationConsoleImage: AllowLiteralOrExpression<string>,
    migrationConsolePullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>,
    base64ConfigContents: AllowLiteralOrExpression<string>,
    command: AllowLiteralOrExpression<string>,
    resources: AllowLiteralOrExpression<ResourceRequirementsType>
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
                            resources,
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
                                "export MIGRATION_USE_SERVICES_YAML_CONFIG=true\n" +
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
}

/**
  * This doesn't yet work because the services yaml that's encoded and emitted isn't formatted
  * for the console to understand it.  See the MigrationConsole template's helper
  * SCRIPT_ARGS_FILL_CONFIG_AND_RUN_TEMPLATE to see the transformation.  We should either
  * 1) have the python code directly handle these new schema types
  * 2) move the helper (jq script) to the console image to invoke it as argo configs are added
  * 3) and(if 2)/or - directly run the jq contents from here too
  *
  * In the meantime, this is here only for posterity.  It's probably a bad idea to spin up new
  * deployments for specifically focused consoles anyway.  We have a `focus` command that would
  * completely obviate this need: https://opensearch.atlassian.net/browse/MIGRATIONS-2704
  */
export const MigrationConsoleDeployment = WorkflowBuilder.create({
    k8sResourceName: "migration-console",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deployConsoleWithConfig", t => t
        .addRequiredInput("command", typeToken<string>())
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("name", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                successCondition: "status.availableReplicas > 0",
                manifest: getConsoleDeploymentResource(b.inputs.name,
                    b.inputs.imageMigrationConsoleLocation,
                    b.inputs.imageMigrationConsolePullPolicy,
                    expr.toBase64(expr.asString(b.inputs.configContents)),
                    b.inputs.command,
                    DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI
                )
            }))

        .addJsonPathOutput("deploymentName", "{.metadata.name}", typeToken<string>())
    )


    .addTemplate("deployConsole", t => t
        .addRequiredInput("command", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addInputsFromRecord(configComponentParameters)
        .addRequiredInput("name", typeToken<string>())
        .addSteps(s => s
            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                c.register(selectInputsForRegister(s, c)))
            .addStep("deployConsoleWithConfig", INTERNAL, "deployConsoleWithConfig", c =>
                c.register({
                    ...selectInputsForRegister(s, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
        .addExpressionOutput("deploymentName",
            c => c.steps.getConsoleConfig.outputs.configContents)
    )

    .getFullScope();
