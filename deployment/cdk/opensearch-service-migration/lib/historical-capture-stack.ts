import {Stack} from "aws-cdk-lib";
import {IVpc, Vpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, ContainerImage, FargateService, FargateTaskDefinition, LogDrivers} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {join} from "path";
import { readFileSync } from "fs"
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {StackPropsExt} from "./stack-composer";

export interface historicalCaptureStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly logstashConfigFilePath: string,
    readonly sourceEndpoint?: string,
}

/**
 * This stack was a short exploratory task into having a deployable Logstash ECS cluster for historical data migration.
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

        const targetEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        let logstashConfigData: string = readFileSync(props.logstashConfigFilePath, 'utf8');
        if (props.sourceEndpoint) {
            logstashConfigData = logstashConfigData.replace("<SOURCE_CLUSTER_HOST>", props.sourceEndpoint)
        }
        logstashConfigData = logstashConfigData.replace("<TARGET_CLUSTER_HOST>", targetEndpoint + ":80")
        // Temporary measure to allow multi-line env variable
        logstashConfigData = logstashConfigData.replace(/(\n)/g, "PUT_LINE")
        // Create Historical Capture Container
        const historicalCaptureImage = new DockerImageAsset(this, "historicalCaptureImage", {
            directory: join(__dirname, "../../..", "docker/logstash-setup")
        });

        const historicalCaptureContainer = historicalCaptureFargateTask.addContainer("historicalCaptureContainer", {
            image: ContainerImage.fromDockerImageAsset(historicalCaptureImage),
            // Add in region and stage
            containerName: "logstash",
            environment: {"LOGSTASH_CONFIG": '' + logstashConfigData},
            logging: LogDrivers.awsLogs({ streamPrefix: 'logstash-lg', logRetention: 30 })
        });

        // Create Fargate Service
        const historicalCaptureFargateService = new FargateService(this, "historicalCaptureFargateService", {
            cluster: ecsCluster,
            taskDefinition: historicalCaptureFargateTask,
            desiredCount: 1
        });

    }
}