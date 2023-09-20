import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc} from "aws-cdk-lib/aws-ec2";
import {Cluster, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";


export interface CaptureProxyESProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly ecsCluster: Cluster,
    readonly mskBrokerEndpoints: string,
    readonly mskClusterArn: string,
    readonly serviceConnectSecurityGroup: ISecurityGroup
    readonly additionalServiceSecurityGroups?: ISecurityGroup[]
}

export class CaptureProxyESStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: CaptureProxyESProps) {
        let securityGroups = [props.serviceConnectSecurityGroup]
        if (props.additionalServiceSecurityGroups) {
            securityGroups = securityGroups.concat(props.additionalServiceSecurityGroups)
        }

        const servicePort: PortMapping = {
            name: "service-port-mapping",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "service-port-mapping",
            //dnsName: "capture-proxy-es",
            port: 9200
        }

        const mskClusterConnectPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [props.mskClusterArn],
            actions: [
                "kafka-cluster:Connect"
            ]
        })
        let mskClusterAllTopicArn = props.mskClusterArn.replace(":cluster", ":topic").concat("/*")
        const mskTopicProducerPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllTopicArn],
            actions: [
                "kafka-cluster:CreateTopic",
                "kafka-cluster:DescribeTopic",
                "kafka-cluster:WriteData"
            ]
        })

        super(scope, id, {
            serviceName: "capture-proxy-es",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            dockerImageCommand: [`/bin/sh -c '/usr/local/bin/docker-entrypoint.sh eswrapper & /runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.Main  --kafkaConnection ${props.mskBrokerEndpoints} --enableMSKAuth --destinationUri https://localhost:19200 --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml & wait -n 1'`],
            securityGroups: securityGroups,
            taskRolePolicies: [mskClusterConnectPolicy, mskTopicProducerPolicy],
            portMappings: [servicePort],
            environment: {
                // Set Elasticsearch port to 19200 to allow capture proxy at port 9200
                "http.port": "19200"
            },
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

}