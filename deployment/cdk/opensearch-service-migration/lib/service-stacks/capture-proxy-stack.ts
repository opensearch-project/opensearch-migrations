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
}

export class CaptureProxyStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: CaptureProxyProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "mskAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/mskAccessSecurityGroupId`))
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

        const mskClusterARN = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/mskClusterARN`);
        const mskClusterConnectPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterARN],
            actions: [
                "kafka-cluster:Connect"
            ]
        })
        // Ideally we should have something like this, but this is actually working on a token value:
        // let mskClusterAllTopicArn = mskClusterARN.replace(":cluster", ":topic").concat("/*")
        const mskTopicProducerPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "kafka-cluster:CreateTopic",
                "kafka-cluster:DescribeTopic",
                "kafka-cluster:WriteData"
            ]
        })

        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/mskBrokers`);
        this.createService({
            serviceName: "capture-proxy",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer/Dockerfile"),
            dockerImageCommand: ['/bin/sh', '-c', `/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy  --kafkaConnection ${brokerEndpoints} --enableMSKAuth --destinationUri https://elasticsearch:9200 --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml`],
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