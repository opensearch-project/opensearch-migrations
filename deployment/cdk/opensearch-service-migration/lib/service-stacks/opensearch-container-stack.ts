import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {PortMapping, Protocol, Ulimit, UlimitName} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";

export interface OpenSearchContainerProps extends StackPropsExt {
    readonly vpc: IVpc
}

/**
 * This is a testing stack which deploys a SINGLE node OpenSearch cluster as the target cluster for this solution with
 * no persistent storage. As such, it should NOT be used for production use cases.
 */
export class OpenSearchContainerStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: OpenSearchContainerProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
        ]

        const servicePort: PortMapping = {
            name: "opensearch-connect",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "opensearch-connect",
            dnsName: "opensearch",
            port: 9200
        }
        const ulimits: Ulimit[] = [
            {
                name: UlimitName.MEMLOCK,
                softLimit: -1,
                hardLimit: -1
            },
            {
                name: UlimitName.NOFILE,
                softLimit: 65536,
                hardLimit: 65536
            }
        ]

        this.createService({
            serviceName: "opensearch",
            dockerImageRegistryName: "opensearchproject/opensearch:2",
            securityGroups: securityGroups,
            environment: {
                "cluster.name": "os-docker-cluster",
                "node.name": "opensearch-node1",
                "discovery.seed_hosts": "opensearch-node1",
                "bootstrap.memory_lock": "true",
                "discovery.type": "single-node"
            },
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ulimits: ulimits,
            ...props
        });
    }

}