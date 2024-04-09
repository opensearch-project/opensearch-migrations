import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "../common-utilities";


export interface ReindexFromSnapshotProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly sourceEndpoint: string,
    readonly fargateCpuArch: CpuArchitecture,
    readonly extraArgs?: string,
    readonly otelCollectorEnabled?: boolean
}

export class ReindexFromSnapshotStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: ReindexFromSnapshotProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)),
        ]

        const artifactS3Arn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/artifactS3Arn`)
        const artifactS3AnyObjectPath = `${artifactS3Arn}/*`
        const artifactS3PublishPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [artifactS3AnyObjectPath],
            actions: [
                "s3:GetObject"
            ]
        })

        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.region, this.account)
        let servicePolicies = [artifactS3PublishPolicy, openSearchPolicy, openSearchServerlessPolicy]

        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        let rfsCommand = `/rfs-app/runJavaWithClasspath.sh com.rfs.ReindexFromSnapshot --snapshot-name rfs-snapshot --min-replicas 0 --lucene-dir '/lucene' --source-host http://elasticsearchsource:9200 --target-host ${osClusterEndpoint} --source-version es_7_10 --target-version os_2_11`
        rfsCommand = props.extraArgs ? rfsCommand.concat(` ${props.extraArgs}`) : rfsCommand

        this.createService({
            serviceName: 'rfs',
            taskInstanceCount: 0,
            dockerDirectoryPath: join(__dirname, "../../../../../", "RFS/docker"),
            dockerImageCommand: ['/bin/sh', '-c', rfsCommand],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

}