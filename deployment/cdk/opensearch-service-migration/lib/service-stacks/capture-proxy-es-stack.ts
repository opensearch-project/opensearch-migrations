import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {ELBTargetGroup, MigrationServiceCore} from "./migration-service-core";
import {StreamingSourceType} from "../streaming-source-type";
import {
    MigrationSSMParameter,
    createMSKProducerIAMPolicies,
    getMigrationStringParameterValue, parseArgsToDict, appendArgIfNotInExtraArgs,
} from "../common-utilities";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";


export interface CaptureProxyESProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly streamingSourceType: StreamingSourceType,
    readonly otelCollectorEnabled: boolean,
    readonly fargateCpuArch: CpuArchitecture,
    readonly targetGroups: ELBTargetGroup[],
    readonly extraArgs?: string,
}

/**
 * The stack for the "capture-proxy-es" service. This service will spin up a Capture Proxy instance
 * and an Elasticsearch with Search Guard instance on a single container. You will also find in this directory these two
 * items split into their own services to give more flexibility in setup.
 */
export class CaptureProxyESStack extends MigrationServiceCore {
    public static readonly DEFAULT_PROXY_PORT = 9200;
    public static readonly DEFAULT_ES_PASSTHROUGH_PORT = 19200;

    constructor(scope: Construct, id: string, props: CaptureProxyESProps) {
        super(scope, id, props)
        const securityGroups = [
            { id: "serviceSG", param: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID },
            { id: "trafficStreamSourceAccessSG", param: MigrationSSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID }
        ].map(({ id, param }) =>
            SecurityGroup.fromSecurityGroupId(this, id, getMigrationStringParameterValue(this, {
                ...props,
                parameter: param,
            }))
        );

        const servicePort: PortMapping = {
            name: "capture-proxy-es-connect",
            hostPort: CaptureProxyESStack.DEFAULT_PROXY_PORT,
            containerPort: CaptureProxyESStack.DEFAULT_PROXY_PORT,
            protocol: Protocol.TCP
        }
        const esServicePort: PortMapping = {
            name: "es-connect",
            hostPort: CaptureProxyESStack.DEFAULT_ES_PASSTHROUGH_PORT,
            containerPort: CaptureProxyESStack.DEFAULT_ES_PASSTHROUGH_PORT,
            protocol: Protocol.TCP
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId) : []

        const brokerEndpoints = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.KAFKA_BROKERS,
        });

        let command = "/usr/local/bin/docker-entrypoint.sh eswrapper & /runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy"
        const extraArgsDict = parseArgsToDict(props.extraArgs)
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--destinationUri", "https://localhost:19200")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--insecureDestination")
        command = appendArgIfNotInExtraArgs(command, extraArgsDict, "--sslConfigFile", "/usr/share/captureProxy/config/proxy_tls.yml")
        if (props.streamingSourceType !== StreamingSourceType.DISABLED) {
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
            serviceName: "capture-proxy-es",
            dockerImageName: "migrations/capture_proxy_es:latest",
            dockerImageCommand: ['/bin/sh', '-c', command.concat(" & wait -n 1")],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            portMappings: [servicePort, esServicePort],
            environment: {
                // Set Elasticsearch port to 19200 to allow capture proxy at port 9200
                "http.port": "19200"
            },
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }
}
