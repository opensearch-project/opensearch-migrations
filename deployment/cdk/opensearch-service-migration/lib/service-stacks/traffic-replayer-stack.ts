import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    ClusterAuth,
    MigrationSSMParameter,
    createMSKConsumerIAMPolicies,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getMigrationStringParameterValue, appendArgIfNotInExtraArgs, parseArgsToDict
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {Duration, SecretValue} from "aws-cdk-lib";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";
import { ECSReplayerYaml } from "../migration-services-yaml";
import { SharedLogFileSystem } from "../components/shared-log-file-system";
import {Secret} from "aws-cdk-lib/aws-secretsmanager";
import { CdkLogger } from "../cdk-logger";
import * as CaptureReplayDashboard from '../components/capture-replay-dashboard.json';
import { MigrationDashboard } from '../constructs/migration-dashboard';

export interface TrafficReplayerProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly clusterAuthDetails: ClusterAuth,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly addOnMigrationId?: string,
    readonly customKafkaGroupId?: string,
    readonly userAgentSuffix?: string,
    readonly extraArgs?: string,
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
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), secretAccessPolicy, openSearchPolicy, openSearchServerlessPolicy]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskConsumerPolicies = createMSKConsumerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskConsumerPolicies)
        }

        const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId
        const osClusterEndpoint = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
        });
        const brokerEndpoints = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.KAFKA_BROKERS,
        });
        const groupId = props.customKafkaGroupId ? props.customKafkaGroupId : `logging-group-${deployId}`

        let command = `/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${osClusterEndpoint}`
        const extraArgsDict = parseArgsToDict(props.extraArgs)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--insecure")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-brokers", brokerEndpoints)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-topic", "logging-traffic-topic")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafka-traffic-group-id", groupId)

        if (props.clusterAuthDetails.basicAuth) {
            let secret;
            if (props.clusterAuthDetails.basicAuth.password) {
                CdkLogger.warn("Password passed in plain text, this is insecure and will leave" +
                    "your password exposed.")
                secret = new Secret(this,"ReplayerClusterPasswordSecret", {
                    secretName: `replayer-user-secret-${props.stage}-${deployId}`,
                    secretStringValue: SecretValue.unsafePlainText(props.clusterAuthDetails.basicAuth.password)
                })
            } else if (props.clusterAuthDetails.basicAuth.password_from_secret_arn) {
                secret = Secret.fromSecretCompleteArn(this, "ReplayerClusterPasswordSecretImport",
                props.clusterAuthDetails.basicAuth.password_from_secret_arn)
            } else {
                throw new Error("Replayer secret or password must be provided if using basic auth.")
            }

            const bashSafeUserAndSecret = `"${props.clusterAuthDetails.basicAuth.username}" "${secret.secretArn}"`
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--auth-header-user-and-secret", bashSafeUserAndSecret)
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
                "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/traffic-replayer-${deployId}`
            },
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });

        this.replayerYaml = new ECSReplayerYaml();
        this.replayerYaml.ecs.cluster_name = `migration-${props.stage}-ecs-cluster`;
        this.replayerYaml.ecs.service_name = `migration-${props.stage}-traffic-replayer-${deployId}`;

        new MigrationDashboard(this, 'CnRDashboard', {
            dashboardName: `MigrationAssistant_CaptureAndReplay_Dashboard_${props.stage}`,
            stage: props.stage,
            account: this.account,
            region: this.region,
            dashboardJson: CaptureReplayDashboard
        });
    }
}
