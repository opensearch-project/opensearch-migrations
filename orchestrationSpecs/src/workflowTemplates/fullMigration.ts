import {z} from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {TemplateBuilder, WFBuilder} from "@/schemas/workflowSchemas";
import {defineParam} from "@/schemas/parameterSchemas";
import {Scope} from "@/schemas/workflowTypes";
import initTlhScript from "resources/targetLatchHelper/init.sh";

function addCommonTargetLatchInputs<C extends Scope>(tb: TemplateBuilder<C, {}, {}, {}>) {
    return tb
        .addRequiredInput("prefix", z.string())
        .addRequiredInput("etcdUtilsImage", z.string())
        .addRequiredInput("etcdUtilsImagePullPolicy", IMAGE_PULL_POLICY);
}
export const TargetLatchHelpers = WFBuilder.create("TargetLatchHelpers")
    .addTemplate("init", t=> t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG))
        .addRequiredInput("configuration", SNAPSHOT_MIGRATION_CONFIG)
        .addContainer(b=>b
            .addImageInfo(b.getInputParam("etcdUtilsImage"), b.getInputParam("etcdUtilsImagePullPolicy"))
            .addCommand(["sh", "-c"])
            .addArgs([initTlhScript])
        )
        .addOutputs(b=>b, true)
        .addOutputs(b=>b, true)
    )
    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("target", CLUSTER_CONFIG)
        .addRequiredInput("processor", z.string())
        .addContainer(b=>b)
    )
    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addContainer(b=>b)
    )
    .getFullScope();

export const FullMigration = WFBuilder.create("FullMigration")
    .addParams(CommonWorkflowParameters)
    .addTemplate("main", t=> t
        .addRequiredInput("sourceMigrationConfigs",
            SNAPSHOT_MIGRATION_CONFIG,
            "List of server configurations to direct migrated traffic toward")
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG),
            "List of server configurations to direct migrated traffic toward")
        .addOptionalInput("imageParams",
            scope =>
                Object.fromEntries(["captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils"]
                        .flatMap((k) => [
                            [`${k}Image`, ""],
                            [`${k}ImagePullPolicy`, "IF_NOT_PRESENT"]
                        ])
                ),
            "OCI image locations and pull policies for required images")
        .addSteps(b => b
            .addStep("init", TargetLatchHelpers, "init", {
                prefix: "foo",
                etcdUtilsImage: "",
                etcdUtilsImagePullPolicy: "IF_NOT_PRESENT",
                targets: [],
                configuration: {
                    indices: [],
                    migrations: []
                }
            })
            .addStep("cleanup", TargetLatchHelpers, "cleanup", {
                prefix: "",
                etcdUtilsImage: "",
                etcdUtilsImagePullPolicy: "IF_NOT_PRESENT"
            })
        )
    )
    .addTemplate("cleanup", t => t
        .addSteps(b=>b)
    )
    .getFullScope();
