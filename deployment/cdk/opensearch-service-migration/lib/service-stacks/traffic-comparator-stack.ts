import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, PortMapping, Protocol, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";


export interface TrafficComparatorProps extends StackPropsExt {
    readonly vpc: IVpc,
}

export class TrafficComparatorStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: TrafficComparatorProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "comparatorSQLAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/comparatorSQLAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: "traffic-comparator-connect",
            hostPort: 9220,
            containerPort: 9220,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "traffic-comparator-connect",
            dnsName: "traffic-comparator",
            port: 9220
        }

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
            serviceName: "traffic-comparator",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficComparator/Dockerfile"),
            dockerImageCommand: ['/bin/sh', '-c', 'cd containerTC && pip3 install --editable . && nc -v -l -p 9220 | tee /dev/stderr | trafficcomparator -vv stream | trafficcomparator dump-to-sqlite --db /shared/comparisons.db'],
            securityGroups: securityGroups,
            volumes: [comparatorSQLVolume],
            mountPoints: [comparatorSQLMountPoint],
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}