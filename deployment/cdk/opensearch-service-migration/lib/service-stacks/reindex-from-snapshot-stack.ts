import {StackPropsExt} from "../stack-composer";
import {Size} from "aws-cdk-lib/core";
import {IVpc, SecurityGroup, EbsDeviceVolumeType} from "aws-cdk-lib/aws-ec2";
import {
    CpuArchitecture,
    ServiceManagedVolume,
    FileSystemType,
    EbsPropagatedTagSource
} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    MigrationSSMParameter,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getSecretAccessPolicy,
    getMigrationStringParameterValue,
    ClusterAuth, parseArgsToDict, appendArgIfNotInExtraArgs, isStackInGovCloud
} from "../common-utilities";
import { RFSBackfillYaml, SnapshotYaml } from "../migration-services-yaml";
import { OtelCollectorSidecar } from "./migration-otel-collector-sidecar";
import { SharedLogFileSystem } from "../components/shared-log-file-system";
import * as rfsDashboard from '../components/reindex-from-snapshot-dashboard.json';
import { MigrationDashboard } from '../constructs/migration-dashboard';

export interface ReindexFromSnapshotProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly fargateCpuArch: CpuArchitecture,
    readonly extraArgs?: string,
    readonly otelCollectorEnabled: boolean,
    readonly clusterAuthDetails: ClusterAuth
    readonly sourceClusterVersion?: string,
    readonly maxShardSizeGiB?: number,
    readonly reindexFromSnapshotWorkerSize: "default" | "maximum",

}

export class ReindexFromSnapshotStack extends MigrationServiceCore {
    rfsBackfillYaml: RFSBackfillYaml;
    rfsSnapshotYaml: SnapshotYaml;

    constructor(scope: Construct, id: string, props: ReindexFromSnapshotProps) {
        super(scope, id, props)

        const securityGroups = [
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
        const storagePath = "/storage"
        const planningSize = props.maxShardSizeGiB ?? 80;
        const planningSizeBuffer = 1.10
        const maxShardSizeGiB = planningSize * planningSizeBuffer
        const maxShardSizeBytes = maxShardSizeGiB * (1024 ** 3)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--s3-local-dir", `"${storagePath}/s3_files"`)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--s3-repo-uri", `"${s3Uri}"`)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--s3-region", this.region)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--snapshot-name", "rfs-snapshot")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--lucene-dir", `"${storagePath}/lucene"`)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--target-host", osClusterEndpoint)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--max-shard-size-bytes", `${Math.ceil(maxShardSizeBytes)}`)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--max-connections", props.reindexFromSnapshotWorkerSize === "maximum" ? "100" : "10")
        if (props.reindexFromSnapshotWorkerSize === "maximum") {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--target-compression")
        }
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
        command = props.extraArgs?.trim() ? command.concat(` ${props.extraArgs?.trim()}`) : command

        const sharedLogFileSystem = new SharedLogFileSystem(this, props.stage, props.defaultDeployId);
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account);
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account);
        const servicePolicies = [sharedLogFileSystem.asPolicyStatement(), artifactS3PublishPolicy, openSearchPolicy, openSearchServerlessPolicy];

        const getSecretsPolicy = props.clusterAuthDetails.basicAuth?.password_from_secret_arn ?
            getSecretAccessPolicy(props.clusterAuthDetails.basicAuth.password_from_secret_arn) : null;
        if (getSecretsPolicy) {
            servicePolicies.push(getSecretsPolicy);
        }

        const volumes = [sharedLogFileSystem.asVolume()];
        const mountPoints = [sharedLogFileSystem.asMountPoint()];

        // Calculate the volume size based on the max shard size
        // Have space for the snapshot and an unpacked copy, with buffer
        const shardVolumeSizeGiBBufferMultiple = 1.10
        const shardVolumeSizeGiB = Math.max(
            Math.ceil(maxShardSizeGiB * 2 * shardVolumeSizeGiBBufferMultiple),
            1
        )

        if (shardVolumeSizeGiB > (16*1024)) {
            // 16 TiB is the maximum volume size for GP3
            throw new Error(`"Your max shard size of ${props.maxShardSizeGiB} GiB is too large to migrate."`)
        }

        // Reserve 5 GiB of storage for system
        const systemStorageGiB = 5
        let ephemeralStorageGiB = systemStorageGiB
        if (isStackInGovCloud(this)) {
            // ECS EBS attachment is not supported in GovCloud
            // https://docs.aws.amazon.com/govcloud-us/latest/UserGuide/govcloud-ecs.html#govcloud-ecs-diffs
            // Use Ephemeral Storage instead, adding size for shard
            ephemeralStorageGiB = Math.ceil(shardVolumeSizeGiB + systemStorageGiB)
            const maxSupportedEphemeralStorageGiB = 200
            if (ephemeralStorageGiB > maxSupportedEphemeralStorageGiB) {
                // Reverse calculations above for max size
                const maxGovCloudSupportedShardSizeGiB = Math.floor((maxSupportedEphemeralStorageGiB-systemStorageGiB)
                    /2/shardVolumeSizeGiBBufferMultiple/planningSizeBuffer)
                throw new Error(`Your max shard size of ${props.maxShardSizeGiB} GiB is too large to migrate ` +
                     `in GovCloud, the max supported is ${maxGovCloudSupportedShardSizeGiB} GiB.`)
            }
        }
        else {
            // Volume we'll use to download and unpack the snapshot
            const snapshotVolume = new ServiceManagedVolume(this, 'SnapshotVolume', {
                name: 'snapshot-volume',
                managedEBSVolume: {
                    size: Size.gibibytes(shardVolumeSizeGiB),
                    volumeType: EbsDeviceVolumeType.GP3,
                    fileSystemType: FileSystemType.XFS,
                    throughput: props.reindexFromSnapshotWorkerSize === "maximum" ? 450 : 125,
                    tagSpecifications: [{
                        tags: {
                            Name: `rfs-snapshot-volume-${props.stage}`,
                        },
                        propagateTags: EbsPropagatedTagSource.SERVICE,
                    }],
                    encrypted: true,
                },
            });

            volumes.push(snapshotVolume);
            mountPoints.push({
                containerPath: storagePath,
                readOnly: false,
                sourceVolume: snapshotVolume.name,
            });
        }

        this.createService({
            serviceName: 'reindex-from-snapshot',
            taskInstanceCount: 0,
            dockerImageName: "migrations/reindex_from_snapshot:latest",
            dockerImageCommand: ['/bin/sh', '-c', "/rfs-app/entrypoint.sh"],
            securityGroups: securityGroups,
            volumes: volumes,
            mountPoints: mountPoints,
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: props.reindexFromSnapshotWorkerSize === "maximum" ? 16 * 1024 : 2 * 1024,
            taskMemoryLimitMiB: props.reindexFromSnapshotWorkerSize === "maximum" ? 32 * 1024 : 4 * 1024,
            ephemeralStorageGiB: ephemeralStorageGiB,
            environment: {
                "RFS_COMMAND": command,
                "RFS_TARGET_USER": targetUser,
                "RFS_TARGET_PASSWORD": targetPassword,
                "RFS_TARGET_PASSWORD_ARN": targetPasswordArn,
                "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/reindex-from-snapshot-${props.defaultDeployId}`,
            },
            ...props
        });

        new MigrationDashboard(this, 'RFSDashboard', {
            dashboardName: `MigrationAssistant_ReindexFromSnapshot_Dashboard_${props.stage}`,
            stage: props.stage,
            account: this.account,
            region: this.region,
            dashboardJson: rfsDashboard
        });

        this.rfsBackfillYaml = new RFSBackfillYaml();
        this.rfsBackfillYaml.ecs.cluster_name = `migration-${props.stage}-ecs-cluster`;
        this.rfsBackfillYaml.ecs.service_name = `migration-${props.stage}-reindex-from-snapshot`;
        this.rfsSnapshotYaml = new SnapshotYaml();
        this.rfsSnapshotYaml.s3 = {repo_uri: s3Uri, aws_region: this.region};
        this.rfsSnapshotYaml.snapshot_name = "rfs-snapshot";
    }
}
