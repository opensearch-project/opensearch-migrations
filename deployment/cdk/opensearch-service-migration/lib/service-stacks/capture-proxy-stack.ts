import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {StreamingSourceType} from "../streaming-source-type";
import {createMSKProducerIAMPolicies} from "../common-utilities";


export interface CaptureProxyProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly customSourceClusterEndpoint?: string,
    readonly analyticsServiceEnabled?: boolean
}

/**
 * The stack for the "capture-proxy" service. This service will spin up a Capture Proxy instance, and will be partially
 * duplicated by the "capture-proxy-es" service which contains a Capture Proxy instance and an Elasticsearch with
 * Search Guard instance.
 */
export class CaptureProxyStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: CaptureProxyProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: "capture-proxy-connect",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "capture-proxy-connect",
            dnsName: "capture-proxy",
            port: 9200
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.region, this.account, props.stage, props.defaultDeployId) : []

        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);
        const sourceClusterEndpoint = props.customSourceClusterEndpoint ? props.customSourceClusterEndpoint : "https://elasticsearch:9200"
        let command = `/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy  --kafkaConnection ${brokerEndpoints} --destinationUri ${sourceClusterEndpoint} --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml`
        command = props.streamingSourceType === StreamingSourceType.AWS_MSK ? command.concat(" --enableMSKAuth") : command
        command = props.analyticsServiceEnabled ? command.concat(" --otelCollectorEndpoint http://otel-collector:4317") : command
        this.createService({
            serviceName: "capture-proxy",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            dockerImageCommand: ['/bin/sh', '-c', command],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}