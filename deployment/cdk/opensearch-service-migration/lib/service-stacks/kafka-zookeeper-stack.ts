import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {
    PortMapping, Protocol,
} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";

export interface KafkaZookeeperProps extends StackPropsExt {
    readonly vpc: IVpc
}

/**
 * This is a non-essential experimental service to test running Kafka within ECS. With this in mind, it should only be
 * used on a test basis.
 */
export class KafkaZookeeperStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: KafkaZookeeperProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: "kafka-zookeeper-connect",
            hostPort: 2181,
            containerPort: 2181,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "kafka-zookeeper-connect",
            dnsName: "kafka-zookeeper",
            port: 2181
        }

        this.createService({
            serviceName: "kafka-zookeeper",
            dockerImageRegistryName: "docker.io/bitnami/zookeeper:3.8",
            securityGroups: securityGroups,
            environment: {
                "ALLOW_ANONYMOUS_LOGIN": "yes"
            },
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 256,
            taskMemoryLimitMiB: 512,
            ...props
        });
    }

}