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


export interface CaptureProxyESProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly analyticsServiceEnabled: boolean
}

/**
 * The stack for the "capture-proxy-es" service. This service will spin up a Capture Proxy instance
 * and an Elasticsearch with Search Guard instance on a single container. You will also find in this directory these two
 * items split into their own services to give more flexibility in setup.
 */
export class CaptureProxyESStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: CaptureProxyESProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: "capture-proxy-es-connect",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "capture-proxy-es-connect",
            dnsName: "capture-proxy-es",
            port: 9200
        }
        const esServicePort: PortMapping = {
            name: "es-connect",
            hostPort: 19200,
            containerPort: 19200,
            protocol: Protocol.TCP
        }
        const esServiceConnectService: ServiceConnectService = {
            portMappingName: "es-connect",
            dnsName: "capture-proxy-es",
            port: 19200
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.region, this.account, props.stage, props.defaultDeployId) : []

        let brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);
        let command = `/usr/local/bin/docker-entrypoint.sh eswrapper & /runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy --kafkaConnection ${brokerEndpoints} --destinationUri https://localhost:19200 --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml`
        command = props.streamingSourceType === StreamingSourceType.AWS_MSK ? command.concat(" --enableMSKAuth") : command
        command = props.analyticsServiceEnabled ? command.concat(" --otelCollectorEndpoint http://otel-collector:4317") : command
        this.createService({
            serviceName: "capture-proxy-es",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            dockerImageCommand: ['/bin/sh', '-c', command.concat(" & wait -n 1")],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            portMappings: [servicePort, esServicePort],
            environment: {
                // Set Elasticsearch port to 19200 to allow capture proxy at port 9200
                "http.port": "19200"
            },
            serviceConnectServices: [serviceConnectService, esServiceConnectService],
            serviceDiscoveryEnabled: true,
            serviceDiscoveryPort: 19200,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

}