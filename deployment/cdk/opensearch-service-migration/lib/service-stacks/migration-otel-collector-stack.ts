import {StackPropsExt} from "../stack-composer";
import {
  BlockDeviceVolume,
  MachineImage,
  SecurityGroup,
  IVpc,
} from "aws-cdk-lib/aws-ec2";
import {PortMapping, Protocol, ServiceConnectService} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {createAwsDistroForOtelPushInstrumentationPolicy} from "../common-utilities";

export interface OtelCollectorProps extends StackPropsExt {
    readonly vpc: IVpc
}

// The OtelCollectorStack is for the OpenTelemetry Collector ECS container
export class OtelCollectorStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: OtelCollectorProps) {
        super(scope, id, props)

        const otelCollectorSecurityGroup = SecurityGroup.fromSecurityGroupId(this, "otelCollectorSGId", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/otelCollectorSGId`))

        // Port Mappings for collector and health check
        const otelCollectorPort: PortMapping = {
          name: "otel-collector-connect",
          hostPort: 4317,
          containerPort: 4317,
          protocol: Protocol.TCP
        }
        const otelHealthCheckPort: PortMapping = {
          name: "otel-healthcheck-connect",
          hostPort: 13133,
          containerPort: 13133,
          protocol: Protocol.TCP
        }
        const serviceConnectServiceCollector: ServiceConnectService = {
          portMappingName: "otel-collector-connect",
          port: 4317,
          dnsName: "otel-collector"
        }
        const serviceConnectServiceHealthCheck: ServiceConnectService = {
          portMappingName: "otel-healthcheck-connect",
          port: 13133,
          dnsName: "otel-healthcheck"
        }

        let securityGroups = [
          SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
          otelCollectorSecurityGroup
        ]

        const servicePolicies = [createAwsDistroForOtelPushInstrumentationPolicy()]

        this.createService({
            serviceName: `otel-collector`,
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/otelCollector"),
            dockerImageCommand: ["--config=/etc/otel-config-aws.yaml"],
            securityGroups: securityGroups,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            portMappings: [otelCollectorPort, otelHealthCheckPort],
            serviceConnectServices: [serviceConnectServiceCollector, serviceConnectServiceHealthCheck],
            serviceDiscoveryEnabled: true,
            serviceDiscoveryPort: 4317,
            taskRolePolicies: servicePolicies,
            ...props
        });
    }

}