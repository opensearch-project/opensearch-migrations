import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, Secret as EcsSecret} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    ClusterAuth,
    ContainerEnvVarNames,
    MigrationSSMParameter,
    createMSKConsumerIAMPolicies,
    createAllAccessOpenSearchIAMAccessPolicy,
    createAllAccessOpenSearchServerlessIAMAccessPolicy,
    getMigrationStringParameterValue, appendArgIfNotInExtraArgs, parseArgsToDict
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {Duration} from "aws-cdk-lib";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";
import { ECSReplayerYaml } from "../migration-services-yaml";
import { SharedLogFileSystem } from "../components/shared-log-file-system";
import {Secret as SecretsManagerSecret} from "aws-cdk-lib/aws-secretsmanager";
import * as CaptureReplayDashboard from '../components/capture-replay-dashboard.json';
import { MigrationDashboard } from '../constructs/migration-dashboard';

export interface TrafficReplayerProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly clusterAuthDetails: ClusterAuth,
    readonly skipClusterCertCheck?: boolean,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly addOnMigrationId?: string,
    readonly customKafkaGroupId?: string,
    readonly userAgentSuffix?: string,
    readonly extraArgs?: string,
    readonly jvmArgs?: string,
    readonly otelCollectorEnabled: boolean,
    readonly maxUptime?: Duration
}

export class TrafficReplayerStack extends MigrationServiceCore {
    replayerYaml: ECSReplayerYaml;

    constructor(scope: Construct, id: string, props: TrafficReplayerProps) {
        super(scope, id, props)

        const securityGroups = [
            { id: "serviceSG", param: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID },
            { id: "trafficStreamSourceAccessSG", param: MigrationSSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID },
            { id: "defaultDomainAccessSG", param: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID },
            { id: "sharedLogsAccessSG", param: MigrationSSMParameter.SHARED_LOGS_SECURITY_GROUP_ID }
        ].map(({ id, param }) =>
            SecurityGroup.fromSecurityGroupId(this, id, getMigrationStringParameterValue(this, {
                ...props,
                parameter: param,
            }))
        );

        const sharedLogFileSystem = new SharedLogFileSystem(this, props.stage, props.defaultDeployId);


        const secretAccessPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "secretsmanager:GetSecretValue",
                "secretsmanager:DescribeSecret"
            ]
        })
        const openSearchPolicy = createAllAccessOpenSearchIAMAccessPolicy()
        const openSearchServerlessPolicy = createAllAccessOpenSearchServerlessIAMAccessPolicy()
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), secretAccessPolicy, openSearchPolicy, openSearchServerlessPolicy]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskConsumerPolicies = createMSKConsumerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskConsumerPolicies)
        }

        const deployId = props.addOnMigrationDeployId ?? props.defaultDeployId
        const osClusterEndpoint = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
        });
        const brokerEndpoints = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.KAFKA_BROKERS,
        });
        const groupId = props.customKafkaGroupId ?? `logging-group-${deployId}`

        let command = `/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${osClusterEndpoint}`
        const extraArgsDict = parseArgsToDict(props.extraArgs)
        if (props.skipClusterCertCheck != false) { // when true or unspecified, add the flag
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--insecure")
        }
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-brokers", brokerEndpoints)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-topic", "logging-traffic-topic")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-group-id", groupId)

        const secrets: Record<string, EcsSecret> = {}
        if (props.clusterAuthDetails.basicAuth) {
            const secret = SecretsManagerSecret.fromSecretCompleteArn(this, "ReplayerTargetSecretImport", props.clusterAuthDetails.basicAuth.user_secret_arn)
            secrets[ContainerEnvVarNames.TARGET_USERNAME] = EcsSecret.fromSecretsManager(secret, "username")
            secrets[ContainerEnvVarNames.TARGET_PASSWORD] = EcsSecret.fromSecretsManager(secret, "password")
        }

        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-enable-msk-auth")
        }
        if (props.userAgentSuffix) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--user-agent", `"${props.userAgentSuffix}"`)
        }
        if (props.clusterAuthDetails.sigv4) {
            const sigv4AuthHeaderServiceRegion = `${props.clusterAuthDetails.sigv4.serviceSigningName},${props.clusterAuthDetails.sigv4.region}`
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--sigv4-auth-header-service-region", sigv4AuthHeaderServiceRegion)
        }
        if (props.otelCollectorEnabled) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--otelCollectorEndpoint", OtelCollectorSidecar.getOtelLocalhostEndpoint())
        }
        command = props.extraArgs?.trim() ? command.concat(` ${props.extraArgs?.trim()}`) : command

        this.createService({
            serviceName: `traffic-replayer-${deployId}`,
            taskInstanceCount: 0,
            dockerImageName: "migrations/traffic_replayer:latest",
            dockerImageCommand: ['/bin/sh', '-c', command],
            securityGroups: securityGroups,
            volumes: [sharedLogFileSystem.asVolume()],
            mountPoints: [sharedLogFileSystem.asMountPoint()],
            taskRolePolicies: servicePolicies,
            environment: {
                "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/traffic-replayer-${deployId}`,
                ...(props.jvmArgs ? { "JDK_JAVA_OPTIONS": props.jvmArgs } : {}),
            },
            secrets: secrets,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 8192,
            taskMemoryLimitMiB: 49152,
            ...props
        });

        this.replayerYaml = new ECSReplayerYaml();
        this.replayerYaml.ecs.cluster_name = `migration-${props.stage}-ecs-cluster`;
        this.replayerYaml.ecs.service_name = `migration-${props.stage}-traffic-replayer-${deployId}`;

        new MigrationDashboard(this, {
            dashboardQualifier: `LiveCaptureReplay_Summary`,
            stage: props.stage,
            account: this.account,
            region: this.region,
            dashboardJson: CaptureReplayDashboard
        });
    }
}
