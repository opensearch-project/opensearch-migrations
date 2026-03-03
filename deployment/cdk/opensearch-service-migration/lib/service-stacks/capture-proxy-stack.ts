import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {ISecurityGroup, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {ELBTargetGroup, MigrationServiceCore} from "./migration-service-core";
import {StreamingSourceType} from "../streaming-source-type";
import {
    MigrationSSMParameter,
    createMSKProducerIAMPolicies,
    getCustomStringParameterValue,
    getMigrationStringParameterValue, parseArgsToDict, appendArgIfNotInExtraArgs,
} from "../common-utilities";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";

export interface CaptureProxyProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly destinationConfig: DestinationConfig,
    readonly otelCollectorEnabled: boolean,
    readonly skipClusterCertCheck?: boolean,
    readonly serviceName?: string,
    readonly targetGroups: ELBTargetGroup[],
    readonly extraArgs?: string,
    readonly jvmArgs?: string,
    readonly taskInstanceCount?: number,
}

interface MigrationSSMDestinationConfig {
    readonly endpointMigrationSSMParameter: MigrationSSMParameter,
    readonly securityGroupMigrationSSMParameter?: MigrationSSMParameter,
}

interface CustomSSMDestinationConfig {
    readonly endpointCustomSSMParameter: string,
    readonly securityGroupCustomSSMParameter?: string,
}

type DestinationConfig = MigrationSSMDestinationConfig | CustomSSMDestinationConfig;

function isMigrationDestinationConfig(config: DestinationConfig): config is MigrationSSMDestinationConfig {
    return (config as MigrationSSMDestinationConfig).endpointMigrationSSMParameter !== undefined;
}

function isCustomDestinationConfig(config: DestinationConfig): config is CustomSSMDestinationConfig {
    return (config as CustomSSMDestinationConfig).endpointCustomSSMParameter !== undefined;
}

function getDestinationSecurityGroup(scope: Construct, config: DestinationConfig, props: CaptureProxyProps): ISecurityGroup | null {
    let securityGroupId: string | null = null;

    if (isMigrationDestinationConfig(config)) {
        securityGroupId = config.securityGroupMigrationSSMParameter
            ? getMigrationStringParameterValue(scope, {
                ...props,
                parameter: config.securityGroupMigrationSSMParameter,
            })
            : null;
    } else if (isCustomDestinationConfig(config)) {
        securityGroupId = config.securityGroupCustomSSMParameter
            ? getCustomStringParameterValue(scope, config.securityGroupCustomSSMParameter)
            : null;
    } else {
        throw new Error('Invalid DestinationConfig provided.');
    }

    return securityGroupId ? SecurityGroup.fromSecurityGroupId(scope, 'destinationSG', securityGroupId) : null;
}

function getDestinationEndpoint(scope: Construct, config: DestinationConfig, props: CaptureProxyProps): string {
    if (isMigrationDestinationConfig(config)) {
        return getMigrationStringParameterValue(scope, {
            ...props,
            parameter: config.endpointMigrationSSMParameter,
        });
    } else if (isCustomDestinationConfig(config)) {
        return getCustomStringParameterValue(scope, config.endpointCustomSSMParameter);
    } else {
        throw new Error('Invalid DestinationConfig provided.');
    }
}

export class CaptureProxyStack extends MigrationServiceCore {
    public static readonly DEFAULT_PROXY_PORT = 9200;

    constructor(scope: Construct, id: string, props: CaptureProxyProps) {
        super(scope, id, props)
        const serviceName = props.serviceName ?? "capture-proxy";

        const securityGroupConfigs = [
            { id: "serviceSG", param: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID },
            { id: "trafficStreamSourceAccessSG", param: MigrationSSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID },
        ];
        const securityGroups = securityGroupConfigs.map(({ id, param }) =>
            SecurityGroup.fromSecurityGroupId(this, id, getMigrationStringParameterValue(this, {
                ...props,
                parameter: param,
            }))
        );
        const destinationSG = getDestinationSecurityGroup(this, props.destinationConfig, props);
        if (destinationSG) {
            securityGroups.push(destinationSG);
        }

        const servicePort: PortMapping = {
            name: `${serviceName}-connect`,
            hostPort: CaptureProxyStack.DEFAULT_PROXY_PORT,
            containerPort: CaptureProxyStack.DEFAULT_PROXY_PORT,
            protocol: Protocol.TCP
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId) : []

        const destinationEndpoint = getDestinationEndpoint(this, props.destinationConfig, props);

        let command = "/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy"

        const extraArgsDict = parseArgsToDict(props.extraArgs)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--destinationUri", destinationEndpoint)
        if (props.skipClusterCertCheck != false) { // when true or unspecified, add the flag
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--insecureDestination")
        }
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--listenPort", "9200")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--sslConfigFile", "/usr/share/captureProxy/config/proxy_tls.yml")
        if (props.streamingSourceType !== StreamingSourceType.DISABLED) {
            const brokerEndpoints = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.KAFKA_BROKERS,
            });
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--kafkaConnection", brokerEndpoints)
        }
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--enableMSKAuth")
        }
        if (props.otelCollectorEnabled) {
            command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--otelCollectorEndpoint", OtelCollectorSidecar.getOtelLocalhostEndpoint())
        }
        command = props.extraArgs?.trim() ? command.concat(` ${props.extraArgs?.trim()}`) : command

        this.createService({
            serviceName: serviceName,
            dockerImageName: "migrations/capture_proxy:latest",
            dockerImageCommand: ['/bin/sh', '-c', command],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            portMappings: [servicePort],
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 2048,
            taskMemoryLimitMiB: 4096,
            ...(props.jvmArgs ? { environment: { "JDK_JAVA_OPTIONS": props.jvmArgs } } : {}),
            ...props
        });
    }
}
