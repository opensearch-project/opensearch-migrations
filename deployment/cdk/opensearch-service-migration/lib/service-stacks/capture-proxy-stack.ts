import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {StreamingSourceType} from "../streaming-source-type";
import {createMSKProducerIAMPolicies} from "../common-utilities";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";
import { IApplicationListener, IApplicationTargetGroup } from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { ALBConfig, isNewALBListenerConfig } from "../common-utilities";

export interface CaptureProxyProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly customSourceClusterEndpoint?: string,
    readonly customSourceClusterEndpointSSMParam?: string,
    readonly otelCollectorEnabled?: boolean,
    readonly albConfig?: ALBConfig,
    readonly serviceName?: string,
    readonly addTargetClusterSG?: boolean,
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
    public static readonly DEFAULT_PROXY_PORT = 9200;

    constructor(scope: Construct, id: string, props: CaptureProxyProps) {
        super(scope, id, props)
        const serviceName = props.serviceName || "capture-proxy";
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`)),
        ]
        if (props.addTargetClusterSG) {
            securityGroups.push(SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)));
        }

        const servicePort: PortMapping = {
            name: `${serviceName}-connect`,
            hostPort: CaptureProxyStack.DEFAULT_PROXY_PORT,
            containerPort: CaptureProxyStack.DEFAULT_PROXY_PORT,
            protocol: Protocol.TCP
        }

        if (props.albConfig) {
            this.albTargetGroup = this.createSecureTargetGroup(serviceName, CaptureProxyStack.DEFAULT_PROXY_PORT, props.vpc);
            if (isNewALBListenerConfig(props.albConfig)) {
                this.albListener = this.createSecureListener(serviceName, props.albConfig.albListenerPort, props.albConfig.alb, props.albConfig.albListenerCert, this.albTargetGroup);
            } else {
                throw new Error("Invalid ALB config");
            }
        }

        const servicePolicies = props.streamingSourceType === StreamingSourceType.AWS_MSK ? createMSKProducerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId) : []

        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);

        const sourceClusterEndpoint = props.customSourceClusterEndpoint ? props.customSourceClusterEndpoint :
                                    props.customSourceClusterEndpointSSMParam ? StringParameter.valueForStringParameter(this, props.customSourceClusterEndpointSSMParam) :
                                    "https://elasticsearch:9200";
        let command = `/runJavaWithClasspath.sh org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy --destinationUri ${sourceClusterEndpoint} --insecureDestination --listenPort 9200 --sslConfigFile /usr/share/elasticsearch/config/proxy_tls.yml`
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
            albTargetGroups: this.albTargetGroup ? [this.albTargetGroup] : [],
            ...props
        });
    }
}