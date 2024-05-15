import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol, ServiceConnectService} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";

export interface KafkaBrokerProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly fargateCpuArch: CpuArchitecture
}

/**
 * This is a non-essential experimental service to test running Kafka within ECS. It has no volume, is a single broker, and will be recreated in
 * dev environment deployments. With this in mind, it should only be used on a test basis.
 */
export class KafkaStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: KafkaBrokerProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: "kafka-connect",
            hostPort: 9092,
            containerPort: 9092,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "kafka-connect",
            dnsName: "kafka",
            port: 9092
        }

        new StringParameter(this, 'SSMParameterKafkaBrokers', {
            description: 'OpenSearch Migration Parameter for Kafka brokers',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`,
            stringValue: 'kafka:9092'
        });

        this.createService({
            serviceName: "kafka",
            dockerImageRegistryName: "docker.io/apache/kafka:3.7.0",
            securityGroups: securityGroups,
            // see https://github.com/apache/kafka/blob/3.7/docker/examples/jvm/single-node/plaintext/docker-compose.yml
            environment: {
                "KAFKA_NODE_ID": "1",
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT',
                "KAFKA_ADVERTISED_LISTENERS": 'PLAINTEXT_HOST://kafka:9092,PLAINTEXT://kafka:19092',
                "KAFKA_PROCESS_ROLES": 'broker,controller',
                "KAFKA_CONTROLLER_QUORUM_VOTERS": '1@localhost:29093',
                "KAFKA_LISTENERS": 'CONTROLLER://:29093,PLAINTEXT_HOST://:9092,PLAINTEXT://:19092',
                "KAFKA_INTER_BROKER_LISTENER_NAME": 'PLAINTEXT',
                "KAFKA_CONTROLLER_LISTENER_NAMES": 'CONTROLLER',
                "CLUSTER_ID": '4L6g3nShT-eMCtK--X86sw',
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
                "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0",
                "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
                "KAFKA_LOG_DIRS": '/tmp/kraft-combined-logs'
            },
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 256,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}