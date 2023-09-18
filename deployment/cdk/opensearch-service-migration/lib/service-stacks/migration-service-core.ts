import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc, SubnetType} from "aws-cdk-lib/aws-ec2";
import {Cluster, ContainerImage, FargateService, FargateTaskDefinition, LogDrivers} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";


export interface MigrationServiceCoreProps extends StackPropsExt {
    readonly serviceName: string,
    readonly vpc: IVpc,
    readonly ecsCluster: Cluster,
    readonly securityGroups: ISecurityGroup[],
    readonly dockerFilePath: string,
    readonly dockerImageCommand?: string,
    readonly environment?: {
        [key: string]: string;
    },
    readonly taskCpuUnits?: number
    readonly taskMemoryLimitMiB?: number
    readonly taskInstanceCount?: number
}

export class MigrationServiceCore extends Stack {

    constructor(scope: Construct, id: string, props: MigrationServiceCoreProps) {
        super(scope, id, props);
        const serviceTaskDef = new FargateTaskDefinition(this, "serviceTaskDef", {
            memoryLimitMiB: props.taskMemoryLimitMiB ? props.taskMemoryLimitMiB : 1024,
            cpu: props.taskCpuUnits ? props.taskCpuUnits : 256
        });

        const serviceImage = new DockerImageAsset(this, "serviceImage", {
            directory: props.dockerFilePath
        });

        const serviceLogGroup = new LogGroup(this, 'serviceLogGroup',  {
            retention: RetentionDays.ONE_MONTH,
            removalPolicy: RemovalPolicy.DESTROY,
            logGroupName: `/migration/${props.stage}-${props.serviceName}`
        });

        const serviceContainer = serviceTaskDef.addContainer("serviceContainer", {
            image: ContainerImage.fromDockerImageAsset(serviceImage),
            containerName: props.serviceName,
            environment: props.environment,
            logging: LogDrivers.awsLogs({
                streamPrefix: `${props.serviceName}-logs`,
                logGroup: serviceLogGroup
            })
        });

        const serviceFargateService = new FargateService(this, "serviceFargateService", {
            serviceName: `migration-${props.stage}-${props.serviceName}`,
            cluster: props.ecsCluster,
            taskDefinition: serviceTaskDef,
            assignPublicIp: true,
            desiredCount: props.taskInstanceCount ? props.taskInstanceCount : 1,
            enableExecuteCommand: true,
            securityGroups: props.securityGroups,
            // This should be confirmed to be a requirement for Service Connect communication, otherwise be Private
            vpcSubnets: props.vpc.selectSubnets({subnetType: SubnetType.PUBLIC}),
            serviceConnectConfiguration: {
                // services: [
                //     {
                //         portMappingName: 'api',
                //         dnsName: 'http-api',
                //         port: 80,
                //     },
                // ],
                logDriver: LogDrivers.awsLogs({
                    streamPrefix: "service-connect-logs",
                    logGroup: serviceLogGroup
                })
            },
        });
        // TODO allow our two options here
        // Error: Namespace must be defined either in serviceConnectConfig or cluster.defaultCloudMapNamespace
        //serviceFargateService.enableServiceConnect();
    }

}