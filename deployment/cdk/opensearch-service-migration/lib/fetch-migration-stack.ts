import {Stack, SecretValue} from "aws-cdk-lib";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, ContainerImage, FargateTaskDefinition, LogDrivers, Secret as ECSSecret} from "aws-cdk-lib/aws-ecs";
import {Secret as SMSecret} from "aws-cdk-lib/aws-secretsmanager";
import {join} from "path";
import {readFileSync} from "fs"
import {StackPropsExt} from "./stack-composer";
import {StringParameter} from "aws-cdk-lib/aws-ssm";

export interface FetchMigrationProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly dpPipelineTemplatePath: string,
    readonly sourceEndpoint: string
}

export class FetchMigrationStack extends Stack {

    constructor(scope: Construct, id: string, props: FetchMigrationProps) {
        super(scope, id, props);

        // Import required values
        const targetClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)

        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        })

        const domainAccessGroupId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)

        // ECS Task Definition
        const fetchMigrationFargateTask = new FargateTaskDefinition(this, "fetchMigrationFargateTask", {
            memoryLimitMiB: 2048,
            cpu: 512
        });

        // Create Fetch Migration Container
        const fetchMigrationContainer = fetchMigrationFargateTask.addContainer("fetchMigrationContainer", {
            image: ContainerImage.fromAsset(join(__dirname, "../../../..", "FetchMigration")),
            // Add in region and stage
            containerName: "fetch-migration",
            logging: LogDrivers.awsLogs({ streamPrefix: 'fetch-migration-lg', logRetention: 30 })
        });

        // Create DP pipeline config from template file
        let dpPipelineData: string = readFileSync(props.dpPipelineTemplatePath, 'utf8');
        dpPipelineData = dpPipelineData.replace("<SOURCE_CLUSTER_HOST>", props.sourceEndpoint);
        dpPipelineData = dpPipelineData.replace("<TARGET_CLUSTER_HOST>", targetClusterEndpoint);
        // Base64 encode
        let encodedPipeline = Buffer.from(dpPipelineData).toString("base64");

        // Create secret using Secrets Manager
        const dpPipelineConfigSecret = new SMSecret(this, "dpPipelineConfigSecret", {
            secretName: `${fetchMigrationFargateTask.family}-${fetchMigrationContainer.containerName}-pipelineConfig`,
            secretStringValue: SecretValue.unsafePlainText(encodedPipeline)
        });
        // Add secret to container
        fetchMigrationContainer.addSecret("INLINE_PIPELINE",
            ECSSecret.fromSecretsManager(dpPipelineConfigSecret)
        );

        // The Migration Domain SG disallows all outgoing, but this prevents the ECS run task
        // command from pulling the Docker image from ECR. Thus, create an SG to allow this.
        // TODO - Limit outbound to only ECR endpoints somehow
        const fetchMigrationSecurityGroup = new SecurityGroup(this, 'fetchMigrationECRAccessSG', {
            vpc: props.vpc
        });

        // // CFN outputs that will be consumed by Copilot
        // new CfnOutput(this, 'FMTaskDefArn', {
        //     exportName: 'FetchMigrationTaskDefinitionArn',
        //     value: fetchMigrationFargateTask.taskDefinitionArn,
        //     description: 'ARN of the ECS Task Definition for Fetch Migration',
        // });
        // new CfnOutput(this, 'FMTaskRoleArn', {
        //     exportName: 'FetchMigrationTaskRoleArn',
        //     value: fetchMigrationFargateTask.taskRole.roleArn,
        //     description: 'ARN of the Task Role for Fetch Migration',
        // });
        // new CfnOutput(this, 'FMExecRoleArn', {
        //     exportName: 'FetchMigrationExecutionRoleArn',
        //     value: fetchMigrationFargateTask.obtainExecutionRole().roleArn,
        //     description: 'ARN of the Execution Role for Fetch Migration',
        // });
        // Documentation - https://docs.aws.amazon.com/cli/latest/reference/ecs/run-task.html#options
        let networkConfigJson = {
            "awsvpcConfiguration": {
                "subnets": props.vpc.publicSubnets.map(_ => _.subnetId),
                "securityGroups": [domainAccessGroupId, fetchMigrationSecurityGroup.securityGroupId]
            }
        }
        let networkConfigString = JSON.stringify(networkConfigJson)
        // Output the ECS run task command using template literals
        let executionCommand = `aws ecs run-task --task-definition ${fetchMigrationFargateTask.taskDefinitionArn}`
        executionCommand += ` --cluster ${ecsCluster.clusterName} --launch-type FARGATE`
        executionCommand += ` --network-configuration '${networkConfigString}'`

        new StringParameter(this, 'SSMParameterFetchMigrationRunTaskCommand', {
            description: 'OpenSearch Migration Parameter CLI command to kick off the Fetch Migration ECS Task',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationCommand`,
            stringValue: executionCommand
        });
    }
}
