import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore, SSMParameter} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "../common-utilities";


export interface ReindexFromSnapshotProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly sourceEndpoint?: string,
    readonly fargateCpuArch: CpuArchitecture,
    readonly extraArgs?: string,
    readonly otelCollectorEnabled?: boolean
}

export class ReindexFromSnapshotStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: ReindexFromSnapshotProps) {
        super(scope, id, props)
        const sourceEndpoint = props.sourceEndpoint ?? this.getStringParameter(SSMParameter.SOURCE_CLUSTER_ENDPOINT, props);

        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", this.getStringParameter(SSMParameter.SERVICE_SECURITY_GROUP_ID, props)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", this.getStringParameter(SSMParameter.OS_ACCESS_SECURITY_GROUP_ID, { stage: props.stage, defaultDeployId: props.defaultDeployId })),
        ]

        const artifactS3Arn = this.getStringParameter(SSMParameter.ARTIFACT_S3_ARN, { stage: props.stage, defaultDeployId: props.defaultDeployId });
        const artifactS3AnyObjectPath = `${artifactS3Arn}/*`
        const artifactS3PublishPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [artifactS3Arn, artifactS3AnyObjectPath],
            actions: [
                "s3:*"
            ]
        })

        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [artifactS3PublishPolicy, openSearchPolicy, openSearchServerlessPolicy]

        const osClusterEndpoint = this.getStringParameter(SSMParameter.OS_CLUSTER_ENDPOINT, { stage: props.stage, defaultDeployId: props.defaultDeployId });
        const s3Uri = `s3://migration-artifacts-${this.account}-${props.stage}-${this.region}/rfs-snapshot-repo`;
        let rfsCommand = `/rfs-app/runJavaWithClasspath.sh com.rfs.ReindexFromSnapshot --s3-local-dir /tmp/s3_files --s3-repo-uri ${s3Uri} --s3-region ${this.region} --snapshot-name rfs-snapshot --min-replicas 1 --enable-persistent-run --lucene-dir '/lucene' --source-host ${sourceEndpoint} --target-host ${osClusterEndpoint} --source-version es_7_10 --target-version os_2_11`
        rfsCommand = props.extraArgs ? rfsCommand.concat(` ${props.extraArgs}`) : rfsCommand

        this.createService({
            serviceName: 'reindex-from-snapshot',
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