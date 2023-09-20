import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc} from "aws-cdk-lib/aws-ec2";
import {
    Cluster,
    MountPoint,
    Volume
} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";


export interface MigrationConsoleProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly ecsCluster: Cluster,
    readonly replayerOutputFileSystemId: string,
    readonly migrationDomainEndpoint: string,
    readonly serviceConnectSecurityGroup: ISecurityGroup
    readonly additionalServiceSecurityGroups?: ISecurityGroup[]
}

export class MigrationConsoleStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        let securityGroups = [props.serviceConnectSecurityGroup]
        if (props.additionalServiceSecurityGroups) {
            securityGroups = securityGroups.concat(props.additionalServiceSecurityGroups)
        }

        const volumeName = "sharedReplayerOutputVolume"
        const replayerOutputEFSVolume: Volume = {
            name: volumeName,
            efsVolumeConfiguration: {
                fileSystemId: props.replayerOutputFileSystemId
            },
        };

        const replayerOutputMountPoint: MountPoint = {
            containerPath: "/shared-replayer-output",
            readOnly: false,
            sourceVolume: volumeName
        }

        super(scope, id, {
            serviceName: "migration-console",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            environment: {
                "MIGRATION_DOMAIN_ENDPOINT": props.migrationDomainEndpoint
            },
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 1024,
            ...props
        });
    }

}