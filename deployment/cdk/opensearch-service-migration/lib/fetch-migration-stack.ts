import {SecretValue, Stack} from "aws-cdk-lib";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {
    Cluster,
    ContainerImage, CpuArchitecture,
    FargateTaskDefinition,
    LogDrivers, OperatingSystemFamily,
    Secret as ECSSecret
} from "aws-cdk-lib/aws-ecs";
import {Secret as SMSecret} from "aws-cdk-lib/aws-secretsmanager";
import {join} from "path";
import {readFileSync} from "fs"
import {StackPropsExt} from "./stack-composer";
import {
    MigrationSSMParameter,
    createDefaultECSTaskRole,
    createMigrationStringParameter,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getMigrationStringParameter,
    getMigrationStringParameterValue
} from "./common-utilities";

export interface FetchMigrationProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly dpPipelineTemplatePath: string,
    readonly fargateCpuArch: CpuArchitecture
}

export class FetchMigrationStack extends Stack {

    constructor(scope: Construct, id: string, props: FetchMigrationProps) {
        super(scope, id, props);
        const sourceEndpoint = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT,
        });

        // Import required values
        const targetClusterEndpoint = getMigrationStringParameter(this, {
            ...props,
            parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
        });
        const domainAccessGroupId = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID,
        });
        // This SG allows outbound access for ECR access as well as communication with other services in the cluster
        const serviceGroupId = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID,
        });
        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        });

        const serviceName = "fetch-migration"
        const ecsTaskRole = createDefaultECSTaskRole(this, serviceName)
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        ecsTaskRole.addToPolicy(openSearchPolicy)
        ecsTaskRole.addToPolicy(openSearchServerlessPolicy)
        // ECS Task Definition
        const fetchMigrationFargateTask = new FargateTaskDefinition(this, "fetchMigrationFargateTask", {
            runtimePlatform: {
                operatingSystemFamily: OperatingSystemFamily.LINUX,
                cpuArchitecture: props.fargateCpuArch
            },
            family: `migration-${props.stage}-${serviceName}`,
            memoryLimitMiB: 8192,
            cpu: 2048,
            taskRole: ecsTaskRole
        });

        createMigrationStringParameter(this, fetchMigrationFargateTask.taskDefinitionArn, {
            ...props,
            parameter: MigrationSSMParameter.FETCH_MIGRATION_TASK_DEF_ARN,
        });
        createMigrationStringParameter(this, fetchMigrationFargateTask.taskRole.roleArn, {
            ...props,
            parameter: MigrationSSMParameter.FETCH_MIGRATION_TASK_ROLE_ARN,
        });
        createMigrationStringParameter(this, fetchMigrationFargateTask.obtainExecutionRole().roleArn, {
            ...props,
            parameter: MigrationSSMParameter.FETCH_MIGRATION_TASK_EXEC_ROLE_ARN,
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
        dpPipelineData = dpPipelineData.replace("<SOURCE_CLUSTER_HOST>", sourceEndpoint);
        // Base64 encode
        let encodedPipeline = Buffer.from(dpPipelineData).toString("base64");

        // Create secret using Secrets Manager
        const dpPipelineConfigSecret = new SMSecret(this, "dpPipelineConfigSecret", {
            secretName: `${props.stage}-${fetchMigrationContainer.containerName}-pipelineConfig`,
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
                "securityGroups": [domainAccessGroupId, serviceGroupId]
            }
        }
        let networkConfigString = JSON.stringify(networkConfigJson)
        // Output the ECS run task command using template literals
        let executionCommand = `aws ecs run-task --task-definition ${fetchMigrationFargateTask.taskDefinitionArn}`
        executionCommand += ` --cluster ${ecsCluster.clusterName} --launch-type FARGATE`
        executionCommand += ` --network-configuration '${networkConfigString}'`

        createMigrationStringParameter(this, executionCommand, {
            ...props,
            parameter: MigrationSSMParameter.FETCH_MIGRATION_COMMAND,
        });
    }
}
