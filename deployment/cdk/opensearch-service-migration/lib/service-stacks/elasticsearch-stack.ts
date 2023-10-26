import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {CloudMapOptions, ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface ElasticsearchProps extends StackPropsExt {
    readonly vpc: IVpc,
}

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
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/elasticsearchWithSearchGuard"),
            securityGroups: securityGroups,
            portMappings: [servicePort],
            serviceConnectServices: [serviceConnectService],
            serviceDiscoveryEnabled: true,
            serviceDiscoveryPort: 9200,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}