import {Stack, StackProps, CfnOutput} from "aws-cdk-lib";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, ContainerImage, FargateService, FargateTaskDefinition, LogDrivers} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {Pass, StateMachine, IntegrationPattern} from "aws-cdk-lib/aws-stepfunctions"
import {EcsRunTask, EcsFargateLaunchTarget} from "aws-cdk-lib/aws-stepfunctions-tasks";
import {join} from "path";
import { readFileSync } from "fs"

export interface historicalCaptureStackProps extends StackProps {
    readonly vpc: IVpc,
    readonly dpPipelineTemplatePath: string,
    readonly sourceEndpoint: string,
    readonly targetEndpoint: string
}

/**
 * This stack is a short exploratory task into having a deployable Fetch-Migration / Data Prepper
 * Step Function workflow that executes the historical data migration task via ECS.
 * NOTE: It should only be used for development purposes in its current state
 */
export class HistoricalCaptureStack extends Stack {

    constructor(scope: Construct, id: string, props: historicalCaptureStackProps) {
        super(scope, id, props);

        const ecsCluster = new Cluster(this, "ecsHistoricalCaptureCluster", {
            vpc: props.vpc
        });

        const historicalCaptureFargateTask = new FargateTaskDefinition(this, "historicalCaptureFargateTask", {
            memoryLimitMiB: 2048,
            cpu: 512
        });

        let dpPipelineData: string = readFileSync(props.dpPipelineTemplatePath, 'utf8');
        dpPipelineData = dpPipelineData.replace("<SOURCE_CLUSTER_HOST>", props.sourceEndpoint);
        dpPipelineData = dpPipelineData.replace("<TARGET_CLUSTER_HOST>", props.targetEndpoint);
        // Base64 encode
        let encodedPipeline = Buffer.from(dpPipelineData).toString("base64");
        // Create Historical Capture Container
        const historicalCaptureImage = new DockerImageAsset(this, "fetchMigrationImage", {
            directory: join(__dirname, "../../..", "docker/fetch-migration")
        });

        const historicalCaptureContainer = historicalCaptureFargateTask.addContainer("historicalCaptureContainer", {
            image: ContainerImage.fromDockerImageAsset(historicalCaptureImage),
            // Add in region and stage
            containerName: "fetch-migration",
            environment: {"INLINE_PIPELINE": '' + encodedPipeline},
            logging: LogDrivers.awsLogs({ streamPrefix: 'fetch-migration-lg', logRetention: 30 })
        });

        // Create Step Function workflow with a RunTask step
        const runTask = new EcsRunTask(this, "historicalCaptureRunTask", {
            cluster: ecsCluster,
            taskDefinition: historicalCaptureFargateTask,
            launchTarget: new EcsFargateLaunchTarget(),
            integrationPattern: IntegrationPattern.RUN_JOB
        });

        const startState = new Pass(this, "historicalCaptureStartState");
        const historicalCaptureWorkflowDef = startState.next(runTask);

        const historicalCaptureStateMachine = new StateMachine(this, "historicalCaptureStateMachine", {
            definition: historicalCaptureWorkflowDef
        });

        // Console output
        let executionCommand: string = "aws stepfunctions start-execution --state-machine-arn "
        new CfnOutput(this, 'HistoricalCaptureExports', {
            value: executionCommand + historicalCaptureStateMachine.stateMachineArn,
            description: 'Exported CLI command to kick off the historical data migration Step Function workflow',
        });

    }
}