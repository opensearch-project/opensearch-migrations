import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {
    PortMapping, Protocol
} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";

export interface KafkaBrokerProps extends StackPropsExt {
    readonly vpc: IVpc
}

/**
 * This is a non-essential experimental service to test running Kafka within ECS. It has no volume, is a single broker, and will be recreated in
 * dev environment deployments. With this in mind, it should only be used on a test basis.
 */
export class KafkaBrokerStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: KafkaBrokerProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: "kafka-broker-connect",
            hostPort: 9092,
            containerPort: 9092,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "kafka-broker-connect",
            dnsName: "kafka-broker",
            port: 9092
        }

        new StringParameter(this, 'SSMParameterKafkaBrokers', {
            description: 'OpenSearch Migration Parameter for Kafka brokers',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`,
            stringValue: 'kafka-broker:9092'
        });

        this.createService({
            serviceName: "kafka-broker",
            dockerImageRegistryName: "docker.io/bitnami/kafka:3.6",
            securityGroups: securityGroups,
            environment: {
                // Definitions for some of these variables can be found in the Bitnami docker documentation here: https://hub.docker.com/r/bitnami/kafka/
                "ALLOW_PLAINTEXT_LISTENER": "yes",
                "KAFKA_ENABLE_KRAFT": "no",
                "KAFKA_ZOOKEEPER_CONNECT": "kafka-zookeeper:2181",
                // Interfaces that Kafka binds to
                "KAFKA_LISTENERS": "PLAINTEXT://:9092",
                // Define the protocol to use per listener name
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "PLAINTEXT:PLAINTEXT",
                // Metadata passed back to clients, that they will use to connect to brokers
                // This is currently only accessible within an ECS service that can connect to kafka-broker
                "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://kafka-broker:9092"
            },
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 256,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}