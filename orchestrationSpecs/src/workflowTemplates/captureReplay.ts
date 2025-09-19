// TODO
import { WorkflowBuilder, type Workflow } from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, getParametersFromTargetConfig,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/sharedTypes";
import {CLUSTER_CONFIG, REPLAYER_OPTIONS, TARGET_CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import {z} from "zod";
import {INTERNAL} from "@/schemas/taskBuilder";
import {SetupKafka} from "@/workflowTemplates/setupKafka";
import expr from "@/schemas/expression";
import {Replayer} from "@/workflowTemplates/replayer";
import {selectInputsFieldsAsExpressionRecord, selectInputsForRegister} from "@/schemas/parameterConversions";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {MISSING_FIELD} from "@/schemas/plainObject";
import {CaptureProxy} from "@/workflowTemplates/captureProxy";


export const CaptureReplay = WorkflowBuilder.create({
    k8sResourceName: "capture-replay",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})


    .addParams(CommonWorkflowParameters)


    .addTemplate("idGenerator", t=>t
        .addRequiredInput("proxyEndpoint", typeToken<string>())
        .addSteps(b=>b)
        .addExpressionOutput("proxyEndpoint", c=>c.inputs.proxyEndpoint)
    )


    .addSuspendTemplate("getUserApproval")


    .addTemplate("getBrokersList", t=>t
        .addRequiredInput("createdBootstrapServers", typeToken<string>())
        .addRequiredInput("createdKafkaName", typeToken<string>())
        .addRequiredInput("providedKafkaBootstrapServers", typeToken<string>())
        .addRequiredInput("providedKafkaK8sName", typeToken<string>())

        .addSteps(b=>b)

        .addExpressionOutput("kafkaName", b=>expr.ternary(
            expr.equals("", b.inputs.providedKafkaK8sName),
            b.inputs.createdKafkaName,
            b.inputs.providedKafkaK8sName
        ))
        .addExpressionOutput("bootstrapServers", b=>expr.ternary(
            expr.equals("", b.inputs.providedKafkaBootstrapServers),
            b.inputs.createdKafkaName,
            b.inputs.providedKafkaK8sName
        ))
    )


    .addTemplate("runAll", t=>t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("proxyDestination", typeToken<string>())
        .addRequiredInput("proxyListenPort", typeToken<number>())

        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addOptionalInput("providedKafkaBootstrapServers", c=>"")
        .addOptionalInput("providedKafkaK8sName", c=>"")
        .addOptionalInput("kafkaPrefix", c=> "capturetraffic")
        .addOptionalInput("topicName", c=> "")
        .addOptionalInput("topicPartitions", c=> 0)
        .addOptionalInput("topicReplicas", c=> 0)

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole","TrafficReplayer","CaptureProxy"]))

        .addDag(b=>b

            .addTask("idGenerator", INTERNAL, "idGenerator", c=>
                c.register({
                    proxyEndpoint: expr.concat(
                        expr.literal("http://"),
                        b.inputs.sessionName,
                        expr.literal(":"),
                        expr.asString(b.inputs.proxyListenPort))
                }))

            .addTask("kafkaClusterSetup", SetupKafka, "clusterDeploy", c=>
                c.register({
                    kafkaName: expr.concat(
                        b.inputs.providedKafkaK8sName,
                        expr.literal("-"),
                        expr.last(expr.split(c.tasks.idGenerator.id, "-"))
                    )
                }),
                {
                    when: expr.equals("", b.inputs.providedKafkaBootstrapServers),
                    dependencies: ["idGenerator"]
                }
            )

            .addTask("getBrokersList", INTERNAL, "getBrokersList", c=>
                c.register({
                    createdBootstrapServers: c.tasks.kafkaClusterSetup.outputs.bootstrapServers,
                    createdKafkaName: c.tasks.kafkaClusterSetup.outputs.kafkaName,
                    providedKafkaBootstrapServers: b.inputs.providedKafkaBootstrapServers,
                    providedKafkaK8sName: b.inputs.providedKafkaK8sName
                }),
                {dependencies: ["kafkaClusterSetup"]}
            )

            .addTask("kafkaTopicSetup", SetupKafka, "createKafkaTopic", c=>
                c.register({
                    kafkaName: c.tasks.getBrokersList.outputs.kafkaName,
                    topicName:
                        expr.ternary(expr.equals(b.inputs.topicName, ""), b.inputs.sessionName, b.inputs.topicName),
                    topicPartitions: b.inputs.topicPartitions,
                    topicReplicas: b.inputs.topicReplicas
                }),
                {dependencies: ["getBrokersList"]}
            )

            .addTask("deployCaptureProxy", CaptureProxy, "deployCaptureProxy", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    listenerPort: b.inputs.proxyListenPort,
                    kafkaConnection: c.tasks.getBrokersList.outputs.bootstrapServers,
                    kafkaTopic: c.tasks.kafkaTopicSetup.outputs.topicName
                }),
                {dependencies: ["getBrokersList", "kafkaTopicSetup"]})

            .addTask("proxyService", CaptureProxy, "deployProxyService", c=>
                c.register({
                    serviceName: b.inputs.sessionName,
                    port: b.inputs.proxyListenPort
                }),
                {dependencies: ["deployCaptureProxy"]}
            )

            .addTask("Replayer", Replayer, "deployReplayerFromConfig", c=>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        kafkaTrafficBrokers: c.tasks.getBrokersList.outputs.bootstrapServers,
                        kafkaTrafficTopic: c.tasks.kafkaTopicSetup.outputs.topicName,
                        kafkaGroupId: c.tasks.idGenerator.id
                    }),
                {dependencies: ["getBrokersList","kafkaTopicSetup"]}
            )

            .addTask("runMigrationConsole", MigrationConsole, "deployConsole", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    name: expr.concat(expr.literal("diagnostic-console-"), b.inputs.sessionName),
                    command: "tail -f /dev/null",
                    kafkaInfo: expr.makeDict({
                        broker_endpoints: c.tasks.getBrokersList.outputs.bootstrapServers,
                        standard: ""
                    }),
                    snapshotConfig: MISSING_FIELD,
                }),
                {dependencies: ["getBrokersList"]}
            )
        )
    )


    .getFullScope();
