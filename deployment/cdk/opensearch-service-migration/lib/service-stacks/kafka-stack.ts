import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import { MigrationSSMParameter, createMigrationStringParameter, getMigrationStringParameterValue } from "../common-utilities";
import {KafkaYaml} from "../migration-services-yaml";

export interface KafkaBrokerProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly fargateCpuArch: CpuArchitecture
}

/**
 * This is a non-essential experimental service to test running Kafka within ECS. It has no volume, is a single broker, and will be recreated in
 * dev environment deployments. With this in mind, it should only be used on a test basis.
 */
export class KafkaStack extends MigrationServiceCore {
    kafkaYaml: KafkaYaml;

    constructor(scope: Construct, id: string, props: KafkaBrokerProps) {
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
            name: "kafka-connect",
            hostPort: 9092,
            containerPort: 9092,
            protocol: Protocol.TCP
        }

        createMigrationStringParameter(this, 'kafka:9092', {
            ...props,
            parameter: MigrationSSMParameter.KAFKA_BROKERS
        });
        this.createService({
            serviceName: "kafka",
            dockerImageName: "mirror.gcr.io/apache/kafka:3.9.1",
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
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 256,
            taskMemoryLimitMiB: 2048,
            ...props
        });
        this.kafkaYaml = new KafkaYaml();
        this.kafkaYaml.standard = '';
        this.kafkaYaml.broker_endpoints = 'kafka:9092';
    }

}
