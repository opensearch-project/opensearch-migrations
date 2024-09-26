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
    ClusterAuth, parseArgsToDict, appendArgIfNotInExtraArgs
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
    readonly sourceClusterVersion?: string
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
        let command = "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments"
        const extraArgsDict = parseArgsToDict(props.extraArgs)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--s3-local-dir", "/tmp/s3_files")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--s3-repo-uri", `"${s3Uri}"`)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--s3-region", this.region)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--snapshot-name", "rfs-snapshot")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--lucene-dir", "/lucene")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--target-host", osClusterEndpoint)
        if (props.clusterAuthDetails.sigv4) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--target-aws-service-signing-name", props.clusterAuthDetails.sigv4.serviceSigningName)
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--target-aws-region", props.clusterAuthDetails.sigv4.region)
        }
        if (props.otelCollectorEnabled) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--otel-collector-endpoint", OtelCollectorSidecar.getOtelLocalhostEndpoint())
        }
        if (props.sourceClusterVersion) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--source-version", `"${props.sourceClusterVersion}"`)
        }

        let targetUser = "";
        let targetPassword = "";
        let targetPasswordArn = "";
        if (props.clusterAuthDetails.basicAuth) {
            // Only set user or password if not overridden in extraArgs
            if (extraArgsDict["--target-username"] === undefined) {
                targetUser = props.clusterAuthDetails.basicAuth.username
            }
            if (extraArgsDict["--target-password"] === undefined) {
                targetPassword = props.clusterAuthDetails.basicAuth.password ?? ""
                targetPasswordArn = props.clusterAuthDetails.basicAuth.password_from_secret_arn ?? ""
            }
        }
        command = props.extraArgs ? command.concat(` ${props.extraArgs}`) : command

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
                "RFS_COMMAND": command,
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
