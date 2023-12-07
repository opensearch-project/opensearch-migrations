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
        // TODO confirm this is needed
        const ulimits: Ulimit[] = [
            {
                name: UlimitName.MEMLOCK,
                softLimit: -1,
                hardLimit: -1
            },
            {
                name: UlimitName.NOFILE,
                softLimit: 1024 * 512,
                hardLimit: 1024 * 512
            }
        ]

        this.createService({
            serviceName: "opensearch",
            dockerImageRegistryName: "opensearchproject/opensearch:2",
            securityGroups: securityGroups,
            environment: {
                "plugins.security.disabled": "true",
                "cluster.name": "os-docker-cluster",
                "node.name": "opensearch-node1",
                "discovery.seed_hosts": "opensearch-node1",
                "bootstrap.memory_lock": "true",
                // TODO confirm these two settings are needed
                "OPENSEARCH_JAVA_OPTS": "-Xms512m -Xmx512m",
                "ES_SETTING_NODE_STORE_ALLOW__MMAP": "false",
                "discovery.type": "single-node"
            },
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            taskCpuUnits: 2048,
            taskMemoryLimitMiB: 8192,
            ulimits: ulimits,
            ...props
        });
    }

}