import {Stack, StackProps, CfnOutput} from "aws-cdk-lib";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Effect, ManagedPolicy, PolicyDocument, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {Cluster, ContainerImage, FargateService, FargateTaskDefinition, LogDrivers} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {join} from "path";
import { readFileSync } from "fs"

export interface historicalCaptureStackProps extends StackProps {
    readonly vpc: IVpc,
    readonly dpPipelineTemplatePath: string,
    readonly sourceEndpoint: string,
    readonly targetEndpoint: string
}

export class HistoricalCaptureStack extends Stack {

    constructor(scope: Construct, id: string, props: historicalCaptureStackProps) {
        super(scope, id, props);

        // ECS Cluster in VPC
        const ecsCluster = new Cluster(this, "ecsHistoricalCaptureCluster", {
            vpc: props.vpc
        });

        // ECS Task Definition
        const historicalCaptureFargateTask = new FargateTaskDefinition(this, "historicalCaptureFargateTask", {
            memoryLimitMiB: 2048,
            cpu: 512
        });

        let dpPipelineData: string = readFileSync(props.dpPipelineTemplatePath, 'utf8');
        dpPipelineData = dpPipelineData.replace("<SOURCE_CLUSTER_HOST>", props.sourceEndpoint);
        dpPipelineData = dpPipelineData.replace("<TARGET_CLUSTER_HOST>", props.targetEndpoint);
        // Base64 encode
        let encodedPipeline = Buffer.from(dpPipelineData).toString("base64");
        
        // Docker image asset
        const historicalCaptureImage = new DockerImageAsset(this, "fetchMigrationImage", {
            directory: join(__dirname, "../../..", "docker/fetch-migration")
        });

        // Create Historical Capture Container
        const historicalCaptureContainer = historicalCaptureFargateTask.addContainer("historicalCaptureContainer", {
            image: ContainerImage.fromDockerImageAsset(historicalCaptureImage),
            // Add in region and stage
            containerName: "fetch-migration",
            environment: {"INLINE_PIPELINE": '' + encodedPipeline},
            logging: LogDrivers.awsLogs({ streamPrefix: 'fetch-migration-lg', logRetention: 30 })
        });

        // The Migration Domain SG disallows all outgoing, but this prevents the ECS run task
        // command from pulling the Docker image from ECR. Thus, create an SG to allow this.
        // TODO - Limit outbound to only ECR endpoints somehow
        const fetchMigrationSecurityGroup = new SecurityGroup(this, 'fetchMigrationECRAccessSG', {
            vpc: ecsCluster.vpc
        });

        // CFN outputs that will be consumed by Copilot
        new CfnOutput(this, 'FMTaskDefArn', {
            exportName: 'FetchMigrationTaskDefinitionArn',
            value: historicalCaptureFargateTask.taskDefinitionArn,
            description: 'ARN of the ECS Task Definition for Fetch Migration',
        });
        new CfnOutput(this, 'FMTaskRoleArn', {
            exportName: 'FetchMigrationTaskRoleArn',
            value: historicalCaptureFargateTask.taskRole.roleArn,
            description: 'ARN of the Task Role for Fetch Migration',
        });
        new CfnOutput(this, 'FMExecRoleArn', {
            exportName: 'FetchMigrationExecutionRoleArn',
            value: historicalCaptureFargateTask.obtainExecutionRole().roleArn,
            description: 'ARN of the Execution Role for Fetch Migration',
        });
        // Documentation - https://docs.aws.amazon.com/cli/latest/reference/ecs/run-task.html#options
        let networkConfigJson = {
            "awsvpcConfiguration": {
                "subnets": ecsCluster.vpc.privateSubnets.map(_ => _.subnetId),
                "securityGroups": [process.env.MIGRATION_DOMAIN_SG_ID, fetchMigrationSecurityGroup.securityGroupId]
            }
        }
        let networkConfigString = JSON.stringify(networkConfigJson)
        // Output the ECS run task command using template literals
        let executionCommand = `aws ecs run-task --task-definition ${historicalCaptureFargateTask.taskDefinitionArn}`
        executionCommand += ` --cluster ${ecsCluster.clusterName} --launch-type FARGATE`
        executionCommand += ` --network-configuration '${networkConfigString}'`
        new CfnOutput(this, 'HistoricalCaptureRunTaskCommand', {
            value: executionCommand,
            description: 'CLI command to kick off the Fetch Migration ECS Task',
        });
    }
}
