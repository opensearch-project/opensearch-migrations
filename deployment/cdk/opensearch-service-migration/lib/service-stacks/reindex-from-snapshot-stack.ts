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
import { RFSBackfillYaml } from "../migration-services-yaml";


export interface ReindexFromSnapshotProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly sourceEndpoint: string,
    readonly fargateCpuArch: CpuArchitecture,
    readonly extraArgs?: string,
    readonly otelCollectorEnabled?: boolean
}

export class ReindexFromSnapshotStack extends MigrationServiceCore {
    rfsBackfillYaml: RFSBackfillYaml;

    constructor(scope: Construct, id: string, props: ReindexFromSnapshotProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)),
        ]

        const artifactS3Arn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/artifactS3Arn`)
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

        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        const s3Uri = `s3://migration-artifacts-${this.account}-${props.stage}-${this.region}/rfs-snapshot-repo`
        let rfsCommand = `/rfs-app/runJavaWithClasspath.sh com.rfs.ReindexFromSnapshot --s3-local-dir /tmp/s3_files --s3-repo-uri ${s3Uri} --s3-region ${this.region} --snapshot-name rfs-snapshot --min-replicas 1 --enable-persistent-run --lucene-dir '/lucene' --source-host ${props.sourceEndpoint} --target-host ${osClusterEndpoint} --source-version es_7_10 --target-version os_2_11`
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

        this.rfsBackfillYaml = new RFSBackfillYaml();
        this.rfsBackfillYaml.ecs.cluster_name = `migration-${props.stage}-ecs-cluster`;
        this.rfsBackfillYaml.ecs.service_name = `migration-${props.stage}-reindex-from-snapshot`;

    }

}
