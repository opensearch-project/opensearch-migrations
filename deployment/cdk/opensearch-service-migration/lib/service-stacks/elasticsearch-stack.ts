import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {ELBTargetGroup, MigrationServiceCore, } from "./migration-service-core";
import { MigrationSSMParameter, getMigrationStringParameterValue } from "../common-utilities";


export interface ElasticsearchProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly fargateCpuArch: CpuArchitecture,
    readonly targetGroups: ELBTargetGroup[]
}

/**
 * The stack for the "elasticsearch" service. This service will spin up a simple Elasticsearch with
 * Search Guard instance for simulating an Elasticsearch cluster. It will be partially duplicated by the
 * "capture-proxy-es" service which contains a Capture Proxy instance and an Elasticsearch with Search Guard instance.
 */
export class ElasticsearchStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: ElasticsearchProps) {
        super(scope, id, props)
        const securityGroups = [
            { id: "serviceSG", param: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID }
        ].map(({ id, param }) =>
            SecurityGroup.fromSecurityGroupId(this, id, getMigrationStringParameterValue(this, {
                ...props,
                parameter: param,
            }))
        );
        const servicePort: PortMapping = {
            name: "elasticsearch-connect",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }

        this.createService({
            serviceName: "elasticsearch",
            dockerImageName: "migrations/elasticsearch_searchguard:latest",
            securityGroups: securityGroups,
            portMappings: [servicePort],
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            ...props
        });
    }

}