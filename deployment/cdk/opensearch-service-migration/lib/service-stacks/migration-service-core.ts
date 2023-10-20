import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc, SubnetType} from "aws-cdk-lib/aws-ec2";
import {
    Cluster,
    ContainerImage,
    FargateService,
    FargateTaskDefinition,
    LogDrivers,
    MountPoint,
    PortMapping,
    Volume
} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";


export interface MigrationServiceCoreProps extends StackPropsExt {
    readonly serviceName: string,
    readonly vpc: IVpc,
    readonly securityGroups: ISecurityGroup[],
    readonly dockerFilePath?: string,
    readonly dockerImageRegistryName?: string,
    readonly dockerImageCommand?: string[],
    readonly taskRolePolicies?: PolicyStatement[],
    readonly mountPoints?: MountPoint[],
    readonly volumes?: Volume[],
    readonly portMappings?: PortMapping[],
    readonly environment?: {
        [key: string]: string;
    },
    readonly serviceConnectServices?: ServiceConnectService[],
    readonly taskCpuUnits?: number
    readonly taskMemoryLimitMiB?: number
    readonly taskInstanceCount?: number
}

export class MigrationServiceCore extends Stack {

    createService(props: MigrationServiceCoreProps) {
        if ((!props.dockerFilePath && !props.dockerImageRegistryName) || (props.dockerFilePath && props.dockerImageRegistryName)) {
            throw new Error(`Exactly one option [dockerFilePath, dockerImageRegistryName] is required to create the "${props.serviceName}" service`)
        }

        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        })

        const serviceTaskRole = new Role(this, `${props.serviceName}-TaskRole`, {
            assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
            description: 'ECS Service Task Role'
        });
        // Add default Task Role policy to allow exec and writing logs
        serviceTaskRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ['*'],
            actions: [
                "logs:CreateLogStream",
                "logs:DescribeLogGroups",
                "logs:DescribeLogStreams",
                "logs:PutLogEvents",
                "ssmmessages:CreateControlChannel",
                "ssmmessages:CreateDataChannel",
                "ssmmessages:OpenControlChannel",
                "ssmmessages:OpenDataChannel"
            ]
        }))
        props.taskRolePolicies?.forEach(policy => serviceTaskRole.addToPolicy(policy))

        const serviceTaskDef = new FargateTaskDefinition(this, "ServiceTaskDef", {
            family: `migration-${props.stage}-${props.serviceName}`,
            memoryLimitMiB: props.taskMemoryLimitMiB ? props.taskMemoryLimitMiB : 1024,
            cpu: props.taskCpuUnits ? props.taskCpuUnits : 256,
            taskRole: serviceTaskRole
        });
        if (props.volumes) {
            props.volumes.forEach(vol => serviceTaskDef.addVolume(vol))
        }

        let serviceImage
        if (props.dockerFilePath) {
            serviceImage = ContainerImage.fromDockerImageAsset(new DockerImageAsset(this, "ServiceImage", {
                directory: props.dockerFilePath
            }))
        }
        else {
            // @ts-ignore
            serviceImage = ContainerImage.fromRegistry(props.dockerImageRegistryName)
        }

        const serviceLogGroup = new LogGroup(this, 'ServiceLogGroup',  {
            retention: RetentionDays.ONE_MONTH,
            removalPolicy: RemovalPolicy.DESTROY,
            logGroupName: `/migration/${props.stage}/${props.defaultDeployId}/${props.serviceName}`
        });

        const serviceContainer = serviceTaskDef.addContainer("ServiceContainer", {
            image: serviceImage,
            containerName: props.serviceName,
            command: props.dockerImageCommand,
            environment: props.environment,
            portMappings: props.portMappings,
            logging: LogDrivers.awsLogs({
                streamPrefix: `${props.serviceName}-logs`,
                logGroup: serviceLogGroup
            })
        });
        if (props.mountPoints) {
            serviceContainer.addMountPoints(...props.mountPoints)
        }

        new FargateService(this, "ServiceFargateService", {
            serviceName: `migration-${props.stage}-${props.serviceName}`,
            cluster: ecsCluster,
            taskDefinition: serviceTaskDef,
            assignPublicIp: true,
            desiredCount: props.taskInstanceCount ? props.taskInstanceCount : 1,
            enableExecuteCommand: true,
            securityGroups: props.securityGroups,
            // This should be confirmed to be a requirement for Service Connect communication, otherwise be Private
            vpcSubnets: props.vpc.selectSubnets({subnetType: SubnetType.PUBLIC}),
            serviceConnectConfiguration: {
                namespace: `migration.${props.stage}.local`,
                services: props.serviceConnectServices ? props.serviceConnectServices : undefined,
                logDriver: LogDrivers.awsLogs({
                    streamPrefix: "service-connect-logs",
                    logGroup: serviceLogGroup
                })
            },
        });
    }

}