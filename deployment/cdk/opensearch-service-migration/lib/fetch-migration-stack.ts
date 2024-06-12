import {SecretValue} from "aws-cdk-lib";
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
    createDefaultECSTaskRole,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "./common-utilities";
import { MigrationServiceCore, SSMParameter } from "./service-stacks";
import { StringParameter } from "aws-cdk-lib/aws-ssm";

export interface FetchMigrationProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly dpPipelineTemplatePath: string,
    readonly sourceEndpoint?: string,
    readonly fargateCpuArch: CpuArchitecture
}

export class FetchMigrationStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: FetchMigrationProps) {
        super(scope, id, props);
        const sourceEndpoint = props.sourceEndpoint ?? this.getStringParameter(SSMParameter.SOURCE_CLUSTER_ENDPOINT, props);

        // Import required values
        const targetClusterEndpoint = StringParameter.fromStringParameterName(this, SSMParameter.OS_CLUSTER_ENDPOINT,
            MigrationServiceCore.getStringParameterName(this, SSMParameter.OS_CLUSTER_ENDPOINT, props));
        const domainAccessGroupId = this.getStringParameter(SSMParameter.OS_ACCESS_SECURITY_GROUP_ID, props);
        // This SG allows outbound access for ECR access as well as communication with other services in the cluster
        const serviceGroupId = this.getStringParameter(SSMParameter.SERVICE_SECURITY_GROUP_ID, props);
        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        })

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

        this.createStringParameter(SSMParameter.FETCH_MIGRATION_TASK_DEF_ARN, fetchMigrationFargateTask.taskDefinitionArn, props);
        this.createStringParameter(SSMParameter.FETCH_MIGRATION_TASK_ROLE_ARN, fetchMigrationFargateTask.taskRole.roleArn, props);
        this.createStringParameter(SSMParameter.FETCH_MIGRATION_TASK_EXEC_ROLE_ARN, fetchMigrationFargateTask.obtainExecutionRole().roleArn, props);
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
                "securityGroups": [domainAccessGroupId, serviceGroupId]
            }
        }
        let networkConfigString = JSON.stringify(networkConfigJson)
        // Output the ECS run task command using template literals
        let executionCommand = `aws ecs run-task --task-definition ${fetchMigrationFargateTask.taskDefinitionArn}`
        executionCommand += ` --cluster ${ecsCluster.clusterName} --launch-type FARGATE`
        executionCommand += ` --network-configuration '${networkConfigString}'`

        this.createStringParameter(SSMParameter.FETCH_MIGRATION_COMMAND, executionCommand, props);
    }   
}
