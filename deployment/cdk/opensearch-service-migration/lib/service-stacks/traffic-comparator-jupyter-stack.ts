import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface TrafficComparatorJupyterProps extends StackPropsExt {
    readonly vpc: IVpc,
}

// TODO add support for ui service
export class TrafficComparatorJupyterStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: TrafficComparatorJupyterProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "comparatorSQLAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/comparatorSQLAccessSecurityGroupId`))
        ]

        const volumeName = "sharedComparatorSQLVolume"
        const comparatorSQLVolume: Volume = {
            name: volumeName,
            efsVolumeConfiguration: {
                fileSystemId: StringParameter.valueForStringParameter(this, `/migration/${props.stage}/comparatorSQLVolumeEFSId`),
                transitEncryption: "ENABLED"
            }
        };
        const comparatorSQLMountPoint: MountPoint = {
            containerPath: "/shared",
            readOnly: false,
            sourceVolume: volumeName
        }

        this.createService({
            serviceName: "traffic-comparator-jupyter",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/jupyterNotebook/Dockerfile"),
            dockerImageCommand: ['/bin/sh', '-c', 'cd containerTC && pip3 install --editable ".[data]" && jupyter notebook --ip=0.0.0.0 --port=8888 --no-browser --allow-root'],
            securityGroups: securityGroups,
            volumes: [comparatorSQLVolume],
            mountPoints: [comparatorSQLMountPoint],
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}