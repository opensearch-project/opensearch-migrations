import {StackPropsExt} from "../stack-composer";
import {IVpc, Port, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {StreamingSourceType} from "../streaming-source-type";
import {createMSKProducerIAMPolicies} from "../common-utilities";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";
import { IApplicationListener, IApplicationLoadBalancer, IApplicationTargetGroup } from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { ICertificate } from "aws-cdk-lib/aws-certificatemanager";


export interface CaptureProxyProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly customSourceClusterEndpoint?: string,
    readonly customSourceClusterEndpointSSMParam?: string,
    readonly otelCollectorEnabled?: boolean,
    readonly alb?: IApplicationLoadBalancer,
    readonly albListenerCert?: ICertificate,
    readonly albListenerPort?: number,
    readonly serviceName?: string,
    readonly extraArgs?: string,
}

/**
 * The stack for the "capture-proxy" service. This service will spin up a Capture Proxy instance, and will be partially
 * duplicated by the "capture-proxy-es" service which contains a Capture Proxy instance and an Elasticsearch with
 * Search Guard instance.
 */
export class CaptureProxyStack extends MigrationServiceCore {
    private readonly albListener?: IApplicationListener;
    private readonly albTargetGroup?: IApplicationTargetGroup;

    constructor(scope: Construct, id: string, props: CaptureProxyProps) {
        super(scope, id, props)
        const serviceName = props.serviceName || "capture-proxy";
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`))
        ]

        const servicePort: PortMapping = {
            name: `${serviceName}-connect`,
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }

        if(props.alb) {
            if (!props.albListenerCert) {
                throw new Error("Must have alb cert defined if specifying alb");
            }
            this.albTargetGroup = this.createSecureTargetGroup(serviceName, 9200, props.vpc);
            this.albListener = this.createSecureListener(serviceName, props.albListenerPort || 9200, props.alb, props.albListenerCert, this.albTargetGroup);
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId) : []

        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);

        const sourceClusterEndpoint = props.customSourceClusterEndpoint ? props.customSourceClusterEndpoint :
                                    props.customSourceClusterEndpointSSMParam ? StringParameter.valueForStringParameter(this, props.customSourceClusterEndpointSSMParam) :
                                    "https://elasticsearch:9200";
        let command = `/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy --destinationUri ${sourceClusterEndpoint} --insecureDestination --listenPort 9200`
        command = props.streamingSourceType !== StreamingSourceType.DISABLED ? command.concat(`  --kafkaConnection ${brokerEndpoints}`) : command
        command = props.streamingSourceType === StreamingSourceType.AWS_MSK ? command.concat(" --enableMSKAuth") : command
        command = props.otelCollectorEnabled ? command.concat(` --otelCollectorEndpoint http://localhost:${OtelCollectorSidecar.OTEL_CONTAINER_PORT}`) : command
        command = props.extraArgs ? command.concat(` ${props.extraArgs}`) : command
        this.createService({
            serviceName: serviceName,
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer"),
            dockerImageCommand: ['/bin/sh', '-c', command],
            securityGroups: securityGroups,
            taskRolePolicies: servicePolicies,
            portMappings: [servicePort],
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 2048,
            albTargetGroups: [this.albTargetGroup],
            ...props
        });
    }

}