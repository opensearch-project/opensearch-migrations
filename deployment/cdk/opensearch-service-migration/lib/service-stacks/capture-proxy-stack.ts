import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {ELBTargetGroup, MigrationServiceCore, SSMParameter} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {StreamingSourceType} from "../streaming-source-type";
import {createMSKProducerIAMPolicies} from "../common-utilities";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";

export interface CaptureProxyProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly customSourceClusterEndpoint?: string,
    readonly customSourceClusterEndpointSSMParam?: string,
    readonly otelCollectorEnabled?: boolean,
    readonly serviceName?: string,
    readonly addTargetClusterSG?: boolean,
    readonly targetGroups: ELBTargetGroup[],
    readonly extraArgs?: string,
}

/**
 * The stack for the "capture-proxy" service. This service will spin up a Capture Proxy instance, and will be partially
 * duplicated by the "capture-proxy-es" service which contains a Capture Proxy instance and an Elasticsearch with
 * Search Guard instance.
 */
export class CaptureProxyStack extends MigrationServiceCore {
    public static readonly DEFAULT_PROXY_PORT = 9200;

    constructor(scope: Construct, id: string, props: CaptureProxyProps) {
        super(scope, id, props)
        const serviceName = props.serviceName || "capture-proxy";
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", this.getStringParameter(SSMParameter.SERVICE_SECURITY_GROUP_ID, props)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", this.getStringParameter(SSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID, props)),
        ];
        
        if (props.addTargetClusterSG) {
            securityGroups.push(SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", this.getStringParameter(SSMParameter.OS_ACCESS_SECURITY_GROUP_ID, props)));
        }

        const servicePort: PortMapping = {
            name: `${serviceName}-connect`,
            hostPort: CaptureProxyStack.DEFAULT_PROXY_PORT,
            containerPort: CaptureProxyStack.DEFAULT_PROXY_PORT,
            protocol: Protocol.TCP
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId) : []

        const brokerEndpoints = this.getStringParameter(SSMParameter.KAFKA_BROKERS, { stage: props.stage, defaultDeployId: props.defaultDeployId });
        const sourceClusterEndpoint = props.customSourceClusterEndpoint ?? 
                (props.customSourceClusterEndpointSSMParam != null ? StringParameter.valueForStringParameter(this, props.customSourceClusterEndpointSSMParam) :
                this.getStringParameter(SSMParameter.SOURCE_CLUSTER_ENDPOINT, props));
        let command = `/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy --destinationUri ${sourceClusterEndpoint} --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml`
        command = props.streamingSourceType !== StreamingSourceType.DISABLED ? command.concat(`  --kafkaConnection ${brokerEndpoints}`) : command
        command = props.streamingSourceType === StreamingSourceType.AWS_MSK ? command.concat(" --enableMSKAuth") : command
        command = props.otelCollectorEnabled ? command.concat(` --otelCollectorEndpoint http://localhost:${OtelCollectorSidecar.OTEL_CONTAINER_PORT}`) : command
        command = props.extraArgs ? command.concat(` ${props.extraArgs}`) : command
        this.createService({
            serviceName: serviceName,
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            dockerImageCommand: ['/bin/sh', '-c', command],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            portMappings: [servicePort],
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }
}