import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface CaptureProxyESProps extends StackPropsExt {
    readonly vpc: IVpc,
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
            SecurityGroup.fromSecurityGroupId(this, "mskAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskAccessSecurityGroupId`))
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

        const mskClusterARN = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskClusterARN`);
        const mskClusterName = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskClusterName`);
        const mskClusterConnectPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterARN],
            actions: [
                "kafka-cluster:Connect"
            ]
        })
        const mskClusterAllTopicArn = `arn:aws:kafka:${props.env?.region}:${props.env?.account}:topic/${mskClusterName}/*`
        const mskTopicProducerPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllTopicArn],
            actions: [
                "kafka-cluster:CreateTopic",
                "kafka-cluster:DescribeTopic",
                "kafka-cluster:WriteData"
            ]
        })

        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskBrokers`);
        this.createService({
            serviceName: "capture-proxy-es",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            // TODO: add otel collector endpoint
            dockerImageCommand: ['/bin/sh', '-c', `/usr/local/bin/docker-entrypoint.sh eswrapper & /runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy  --kafkaConnection ${brokerEndpoints} --enableMSKAuth --destinationUri https://localhost:19200 --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml & wait -n 1`],
            securityGroups: securityGroups,
            taskRolePolicies: [mskClusterConnectPolicy, mskTopicProducerPolicy],
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