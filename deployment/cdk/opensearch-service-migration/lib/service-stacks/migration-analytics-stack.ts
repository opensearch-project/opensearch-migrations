import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, PortMapping, Protocol, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {OpenSearchDomainStack} from "../opensearch-domain-stack";



export interface MigrationAnalyticsProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly extraArgs?: string,
    readonly otelConfigFilePath: string

}

// The MigrationAnalyticsStack consists of the OpenTelemetry Collector ECS container & an
// OpenSearch cluster with dashboard.
export class MigrationAnalyticsStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: MigrationAnalyticsProps) {
        super(scope, id, props)

        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            // SecurityGroup.fromSecurityGroupId(this, "analyticsDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAnalyticsAccessSecurityGroupId`)),
        ]

        const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId

        const otelConfigFile = null; // TODO

        this.createService({
            serviceName: `otel-collector-${deployId}`,
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/otelcol"),
            securityGroups: securityGroups,
            // environment: {
            //     "TUPLE_DIR_PATH": `/shared-replayer-output/traffic-replayer-${deployId}`
            // },
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

    // const openSearchAnalyticsStack = new OpenSearchDomainStack(scope, `openSearchAnalyticsStack-${deployId}`,
    // {
    //     ...props
    // })

}