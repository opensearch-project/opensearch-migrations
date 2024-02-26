import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface ElasticsearchProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly fargateCpuArch: CpuArchitecture
}

/**
 * The stack for the "elasticsearch" service. This service will spin up a simple Elasticsearch with
 * Search Guard instance for simulating an Elasticsearch cluster. It will be partially duplicated by the
 * "capture-proxy-es" service which contains a Capture Proxy instance and an Elasticsearch with Search Guard instance.
 */
export class ElasticsearchStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: ElasticsearchProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
        ]

        const servicePort: PortMapping = {
            name: "elasticsearch-connect",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }
        const serviceConnectService: ServiceConnectService = {
            portMappingName: "elasticsearch-connect",
            dnsName: "elasticsearch",
            port: 9200
        }

        this.createService({
            serviceName: "elasticsearch",
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/elasticsearchWithSearchGuard"),
            securityGroups: securityGroups,
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            serviceDiscoveryEnabled: true,
            serviceDiscoveryPort: 9200,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}