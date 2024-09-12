import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    ClusterAuth,
    MigrationSSMParameter,
    createMSKConsumerIAMPolicies,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getMigrationStringParameterValue, parseAndMergeArgs
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import { Duration } from "aws-cdk-lib";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";
import { ECSReplayerYaml } from "../migration-services-yaml";
import { SharedLogFileSystem } from "../components/shared-log-file-system";


export interface TrafficReplayerProps extends StackPropsExt {
    readonly vpc: IVpc,
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

        let securityGroups = [
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

        let replayerCommand = `/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${osClusterEndpoint} --insecure --kafka-traffic-brokers ${brokerEndpoints} --kafka-traffic-topic logging-traffic-topic --kafka-traffic-group-id ${groupId}`
        if (props.clusterAuthDetails.basicAuth) {
            replayerCommand = replayerCommand.concat(` --auth-header-user-and-secret "${props.clusterAuthDetails.basicAuth.username} ${props.clusterAuthDetails.basicAuth.password_from_secret_arn}"`)
        }
        replayerCommand = props.streamingSourceType === StreamingSourceType.AWS_MSK ? replayerCommand.concat(" --kafka-traffic-enable-msk-auth") : replayerCommand
        replayerCommand = props.userAgentSuffix ? replayerCommand.concat(` --user-agent ${props.userAgentSuffix}`) : replayerCommand
        replayerCommand = props.clusterAuthDetails.sigv4 ? replayerCommand.concat(` --sigv4-auth-header-service-region ${props.clusterAuthDetails.sigv4.serviceSigningName},${props.clusterAuthDetails.sigv4.region}`) : replayerCommand
        replayerCommand = props.otelCollectorEnabled ? replayerCommand.concat(` --otelCollectorEndpoint ${OtelCollectorSidecar.getOtelLocalhostEndpoint()}`) : replayerCommand
        replayerCommand = parseAndMergeArgs(replayerCommand, props.extraArgs);

        this.createService({
            serviceName: `traffic-replayer-${deployId}`,
            taskInstanceCount: 0,
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficReplayer"),
            dockerImageCommand: ['/bin/sh', '-c', replayerCommand],
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
    }
}
