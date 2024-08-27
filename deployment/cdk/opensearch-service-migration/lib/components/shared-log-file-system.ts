import { MountPoint, Volume } from 'aws-cdk-lib/aws-ecs';
import { Effect, PolicyStatement } from 'aws-cdk-lib/aws-iam';
import { MigrationSSMParameter, getMigrationStringParameterValue } from '../common-utilities';
import { MigrationServiceCore } from '../service-stacks';


export class SharedLogFileSystem {

    readonly volumeId: string;
    readonly volumeName = "sharedLogsVolume";
    public readonly mountPointPath = "/shared-logs-output";
    constructor(private stack: MigrationServiceCore, stage: string, defaultDeployId: string) {
        this.volumeId = getMigrationStringParameterValue(stack, {
            stage,
            defaultDeployId,
            parameter: MigrationSSMParameter.SHARED_LOGS_EFS_ID,
        })
    }

    asVolume(): Volume {
        return {
            name: this.volumeName,
            efsVolumeConfiguration: {
                fileSystemId: this.volumeId,
                transitEncryption: "ENABLED"
            }
        };
    }

    asMountPoint(): MountPoint {
        return {
            containerPath: this.mountPointPath,
            readOnly: false,
            sourceVolume: this.volumeName
        };
    }

    asPolicyStatement(): PolicyStatement {
        const sharedLogFileSystemArn = `arn:${this.stack.partition}:elasticfilesystem:${this.stack.region}:${this.stack.account}:file-system/${this.volumeId}`
        return new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [sharedLogFileSystemArn],
            actions: [
                "elasticfilesystem:ClientMount",
                "elasticfilesystem:ClientWrite"
            ]
        });
    }
}
