import {Stack, SecretValue} from "aws-cdk-lib";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {
    Cluster,
    ContainerImage,
    FargateTaskDefinition,
    LogDrivers,
    Secret as ECSSecret
} from "aws-cdk-lib/aws-ecs";
import {Secret as SMSecret} from "aws-cdk-lib/aws-secretsmanager";
import {join} from "path";
import {readFileSync} from "fs"
import {StackPropsExt} from "./stack-composer";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {
    createDefaultECSTaskRole,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "./common-utilities";

export interface FetchMigrationProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly dpPipelineTemplatePath: string,
    readonly sourceEndpoint: string
}

export class FetchMigrationStack extends Stack {

    constructor(scope: Construct, id: string, props: FetchMigrationProps) {
        super(scope, id, props);

        // Import required values
        const targetClusterEndpoint = StringParameter.fromStringParameterName(this, "targetClusterEndpoint", `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        const domainAccessGroupId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)
        // This SG allows outbound access for ECR access as well as communication with other services in the cluster
        const serviceConnectGroupId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)

        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        })

        const serviceName = "fetch-migration"
        const ecsTaskRole = createDefaultECSTaskRole(this, serviceName)
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.region, this.account)
        ecsTaskRole.addToPolicy(openSearchPolicy)
        ecsTaskRole.addToPolicy(openSearchServerlessPolicy)
        // ECS Task Definition
        const fetchMigrationFargateTask = new FargateTaskDefinition(this, "fetchMigrationFargateTask", {
            family: `migration-${props.stage}-${serviceName}`,
            memoryLimitMiB: 4096,
            cpu: 1024,
            taskRole: ecsTaskRole
        });

        new StringParameter(this, 'SSMParameterFetchMigrationTaskDefArn', {
            description: 'OpenSearch Migration Parameter for Fetch Migration task definition ARN',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskDefArn`,
            stringValue: fetchMigrationFargateTask.taskDefinitionArn
        });
        new StringParameter(this, 'SSMParameterFetchMigrationTaskRoleArn', {
            description: 'OpenSearch Migration Parameter for Fetch Migration task role ARN',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskRoleArn`,
            stringValue: fetchMigrationFargateTask.taskRole.roleArn
        });
        new StringParameter(this, 'SSMParameterFetchMigrationTaskExecRoleArn', {
            description: 'OpenSearch Migration Parameter for Fetch Migration task exec role ARN',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskExecRoleArn`,
            stringValue: fetchMigrationFargateTask.obtainExecutionRole().roleArn
        });

        // Create Fetch Migration Container
        const fetchMigrationContainer = fetchMigrationFargateTask.addContainer("fetchMigrationContainer", {
            image: ContainerImage.fromAsset(join(__dirname, "../../../..", "FetchMigration")),
            containerName: serviceName,
            logging: LogDrivers.awsLogs({ streamPrefix: 'fetch-migration-lg', logRetention: 30 })
        });

        // Create DP pipeline config from template file
        let dpPipelineData: string = readFileSync(props.dpPipelineTemplatePath, 'utf8');
        // Replace only source cluster host - target host will be overridden by inline env var
        dpPipelineData = dpPipelineData.replace("<SOURCE_CLUSTER_HOST>", props.sourceEndpoint);
        // Base64 encode
        let encodedPipeline = Buffer.from(dpPipelineData).toString("base64");

        // Create secret using Secrets Manager
        const dpPipelineConfigSecret = new SMSecret(this, "dpPipelineConfigSecret", {
            secretName: `${props.stage}-${props.defaultDeployId}-${fetchMigrationContainer.containerName}-pipelineConfig`,
            secretStringValue: SecretValue.unsafePlainText(encodedPipeline)
        });
        // Add secrets to container
        fetchMigrationContainer.addSecret("INLINE_PIPELINE",
            ECSSecret.fromSecretsManager(dpPipelineConfigSecret)
        );
        fetchMigrationContainer.addSecret("INLINE_TARGET_HOST",
            ECSSecret.fromSsmParameter(targetClusterEndpoint)
        );

        let networkConfigJson = {
            "awsvpcConfiguration": {
                "subnets": props.vpc.privateSubnets.map(_ => _.subnetId),
                "securityGroups": [domainAccessGroupId, serviceConnectGroupId]
            }
        }
        let networkConfigString = JSON.stringify(networkConfigJson)
        // Output the ECS run task command using template literals
        let executionCommand = `aws ecs run-task --task-definition ${fetchMigrationFargateTask.taskDefinitionArn}`
        executionCommand += ` --cluster ${ecsCluster.clusterName} --launch-type FARGATE`
        executionCommand += ` --network-configuration '${networkConfigString}'`

        new StringParameter(this, 'SSMParameterFetchMigrationRunTaskCommand', {
            description: 'OpenSearch Migration Parameter for CLI command to kick off the Fetch Migration ECS Task',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationCommand`,
            stringValue: executionCommand
        });
    }
}
