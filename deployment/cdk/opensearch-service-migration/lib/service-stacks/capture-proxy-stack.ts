import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface CaptureProxyProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly customSourceClusterEndpoint?: string
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
            SecurityGroup.fromSecurityGroupId(this, "mskAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskAccessSecurityGroupId`))
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
        const sourceClusterEndpoint = props.customSourceClusterEndpoint ? props.customSourceClusterEndpoint : "https://elasticsearch:9200"
        this.createService({
            serviceName: "capture-proxy",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            // TODO: add otel collector endpoint
            dockerImageCommand: ['/bin/sh', '-c', `/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy  --kafkaConnection ${brokerEndpoints} --enableMSKAuth --destinationUri ${sourceClusterEndpoint} --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml`],
            securityGroups: securityGroups,
            taskRolePolicies: [mskClusterConnectPolicy, mskTopicProducerPolicy],
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}