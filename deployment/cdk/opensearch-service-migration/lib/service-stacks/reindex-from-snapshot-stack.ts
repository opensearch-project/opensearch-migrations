import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    MigrationSSMParameter,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getTargetPasswordAccessPolicy,
    getMigrationStringParameterValue,
    parseAndMergeArgs,
    ClusterAuth
} from "../common-utilities";
import { RFSBackfillYaml, SnapshotYaml } from "../migration-services-yaml";
import { OtelCollectorSidecar } from "./migration-otel-collector-sidecar";
import { SharedLogFileSystem } from "../components/shared-log-file-system";


export interface ReindexFromSnapshotProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly fargateCpuArch: CpuArchitecture,
    readonly extraArgs?: string,
    readonly otelCollectorEnabled: boolean,
    readonly clusterAuthDetails: ClusterAuth
}

export class ReindexFromSnapshotStack extends MigrationServiceCore {
    rfsBackfillYaml: RFSBackfillYaml;
    rfsSnapshotYaml: SnapshotYaml;

    constructor(scope: Construct, id: string, props: ReindexFromSnapshotProps) {
        super(scope, id, props)

        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID,
            })),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID,
            })),
            SecurityGroup.fromSecurityGroupId(this, "sharedLogsAccessSG", getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.SHARED_LOGS_SECURITY_GROUP_ID,
            })),
        ]

        const artifactS3Arn = getMigrationStringParameterValue(this, {
            parameter: MigrationSSMParameter.ARTIFACT_S3_ARN,
            stage: props.stage,
            defaultDeployId: props.defaultDeployId
        });
        const artifactS3AnyObjectPath = `${artifactS3Arn}/*`
        const artifactS3PublishPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [artifactS3Arn, artifactS3AnyObjectPath],
            actions: [
                "s3:*"
            ]
        })

        const osClusterEndpoint = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
        });
        const s3Uri = `s3://migration-artifacts-${this.account}-${props.stage}-${this.region}/rfs-snapshot-repo`;
        let rfsCommand = `/rfs-app/runJavaWithClasspath.sh com.rfs.RfsMigrateDocuments --s3-local-dir /tmp/s3_files --s3-repo-uri ${s3Uri} --s3-region ${this.region} --snapshot-name rfs-snapshot --lucene-dir '/lucene' --target-host ${osClusterEndpoint}`
        rfsCommand = props.clusterAuthDetails.sigv4 ? rfsCommand.concat(`--target-aws-service-signing-name ${props.clusterAuthDetails.sigv4.serviceSigningName} --target-aws-region ${props.clusterAuthDetails.sigv4.region}`) : rfsCommand
        rfsCommand = props.otelCollectorEnabled ? rfsCommand.concat(` --otel-collector-endpoint ${OtelCollectorSidecar.getOtelLocalhostEndpoint()}`) : rfsCommand
        rfsCommand = parseAndMergeArgs(rfsCommand, props.extraArgs);

        let targetUser = "";
        let targetPassword = "";
        let targetPasswordArn = "";
        if (props.clusterAuthDetails.basicAuth) {
            targetUser = props.clusterAuthDetails.basicAuth.username,
            targetPassword = props.clusterAuthDetails.basicAuth.password || "",
            targetPasswordArn = props.clusterAuthDetails.basicAuth.password_from_secret_arn || ""
        };
        const sharedLogFileSystem = new SharedLogFileSystem(this, props.stage, props.defaultDeployId);
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account);
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account);
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), artifactS3PublishPolicy, openSearchPolicy, openSearchServerlessPolicy];

        const getSecretsPolicy = props.clusterAuthDetails.basicAuth?.password_from_secret_arn ?
            getTargetPasswordAccessPolicy(props.clusterAuthDetails.basicAuth.password_from_secret_arn) : null;
        if (getSecretsPolicy) {
            servicePolicies.push(getSecretsPolicy);
        }

        this.createService({
            serviceName: 'reindex-from-snapshot',
            taskInstanceCount: 0,
            dockerDirectoryPath: join(__dirname, "../../../../../", "DocumentsFromSnapshotMigration/docker"),
            dockerImageCommand: ['/bin/sh', '-c', "/rfs-app/entrypoint.sh"],
            securityGroups: securityGroups,
            volumes: [sharedLogFileSystem.asVolume()],
            mountPoints: [sharedLogFileSystem.asMountPoint()],
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 2048,
            taskMemoryLimitMiB: 4096,
            ephemeralStorageGiB: 200,
            environment: {
                "RFS_COMMAND": rfsCommand,
                "RFS_TARGET_USER": targetUser,
                "RFS_TARGET_PASSWORD": targetPassword,
                "RFS_TARGET_PASSWORD_ARN": targetPasswordArn,
                "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/reindex-from-snapshot-${props.defaultDeployId}`,
            },
            ...props
        });

        this.rfsBackfillYaml = new RFSBackfillYaml();
        this.rfsBackfillYaml.ecs.cluster_name = `migration-${props.stage}-ecs-cluster`;
        this.rfsBackfillYaml.ecs.service_name = `migration-${props.stage}-reindex-from-snapshot`;
        this.rfsSnapshotYaml = new SnapshotYaml();
        this.rfsSnapshotYaml.s3 = {repo_uri: s3Uri, aws_region: this.region};
        this.rfsSnapshotYaml.snapshot_name = "rfs-snapshot";
    }
}
