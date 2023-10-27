import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";


export interface TrafficComparatorJupyterProps extends StackPropsExt {
    readonly vpc: IVpc,
}

/**
 * TODO: Add additional infrastructure to setup a public endpoint for this service
 */
export class TrafficComparatorJupyterStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: TrafficComparatorJupyterProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "comparatorSQLAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/comparatorSQLAccessSecurityGroupId`))
        ]

        const volumeName = "sharedComparatorSQLVolume"
        const volumeId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/comparatorSQLVolumeEFSId`)
        const comparatorSQLVolume: Volume = {
            name: volumeName,
            efsVolumeConfiguration: {
                fileSystemId: volumeId,
                transitEncryption: "ENABLED"
            }
        };
        const comparatorSQLMountPoint: MountPoint = {
            containerPath: "/shared",
            readOnly: false,
            sourceVolume: volumeName
        }
        const comparatorEFSArn = `arn:aws:elasticfilesystem:${props.env?.region}:${props.env?.account}:file-system/${volumeId}`
        const comparatorEFSMountPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: [comparatorEFSArn],
            actions: [
                "elasticfilesystem:ClientMount",
                "elasticfilesystem:ClientWrite"
            ]
        })

        this.createService({
            serviceName: "traffic-comparator-jupyter",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/jupyterNotebook"),
            dockerImageCommand: ['/bin/sh', '-c', 'cd containerTC && pip3 install --editable ".[data]" && jupyter notebook --ip=0.0.0.0 --port=8888 --no-browser --allow-root'],
            securityGroups: securityGroups,
            volumes: [comparatorSQLVolume],
            mountPoints: [comparatorSQLMountPoint],
            taskRolePolicies: [comparatorEFSMountPolicy],
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}