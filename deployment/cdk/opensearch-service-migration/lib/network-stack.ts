import {
    GatewayVpcEndpoint,
    GatewayVpcEndpointAwsService,
    InterfaceVpcEndpoint,
    InterfaceVpcEndpointAwsService,
    IpAddresses, IVpc, Port, SecurityGroup,
    SubnetType,
    Vpc,
    SubnetSelection,
    SubnetFilter
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {StackPropsExt} from "./stack-composer";
import { ApplicationListener, ApplicationLoadBalancer, ApplicationProtocol, ApplicationProtocolVersion, ApplicationTargetGroup, IApplicationLoadBalancer, IApplicationTargetGroup, ListenerAction, SslPolicy } from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { Certificate, ICertificate } from "aws-cdk-lib/aws-certificatemanager";
import { ARecord, HostedZone, RecordTarget } from "aws-cdk-lib/aws-route53";
import { LoadBalancerTarget } from "aws-cdk-lib/aws-route53-targets";
import { AcmCertificateImporter } from "./service-stacks/acm-cert-importer";
import { Stack } from "aws-cdk-lib";
import { createMigrationStringParameter, getMigrationStringParameterName, isStackInGovCloud, MigrationSSMParameter } from "./common-utilities";
import { StringParameter } from "aws-cdk-lib/aws-ssm";
import { CdkLogger } from "./cdk-logger";

export interface NetworkStackProps extends StackPropsExt {
    readonly vpcId?: string;
    readonly vpcSubnetIds?: string[];
    readonly vpcAZCount?: number;
    readonly elasticsearchServiceEnabled?: boolean;
    readonly captureProxyServiceEnabled?: boolean;
    readonly targetClusterProxyServiceEnabled?: boolean;
    readonly captureProxyESServiceEnabled?: boolean;
    readonly migrationAPIEnabled?: boolean;
    readonly sourceClusterDisabled?: boolean;
    readonly sourceClusterEndpoint?: string;
    readonly targetClusterEndpoint?: string;
    readonly targetClusterUsername?: string;
    readonly targetClusterPasswordSecretArn?: string;
    readonly albAcmCertArn?: string;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    readonly env?: Record<string, any>;
}

export class VpcDetails {
    public readonly subnetSelection: SubnetSelection;
    public readonly vpc: IVpc;
    
    constructor(vpc: IVpc,vpcSubnetIds?: string[]) {
        this.vpc = vpc;
        
        if (vpcSubnetIds) {
            this.subnetSelection = [SubnetFilter.byIds(vpcSubnetIds)]
        } else {
            this.subnetSelection = {
                subnets: vpc.selectSubnets.PRIVATE_WITH_EGRESS
            };
        }
    }
}

export class NetworkStack extends Stack {
    public readonly albSourceProxyTG: IApplicationTargetGroup;
    public readonly albTargetProxyTG: IApplicationTargetGroup;
    public readonly albSourceClusterTG: IApplicationTargetGroup;
    public readonly vpcDetails: VpcDetails;

    private validateVPC(vpc: IVpc) {
        let uniqueAzPrivateSubnets: string[] = []
        if (vpc.privateSubnets.length > 0) {
            uniqueAzPrivateSubnets = vpc.selectSubnets({
                subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                onePerAz: true
            }).subnetIds
        }
        CdkLogger.info(`Detected VPC with ${vpc.privateSubnets.length} private subnets, ${vpc.publicSubnets.length} public subnets, and ${vpc.isolatedSubnets.length} isolated subnets`)
        if (uniqueAzPrivateSubnets.length < 2) {
            throw new Error(`Not enough AZs (${uniqueAzPrivateSubnets.length} unique AZs detected) used for private subnets to meet 2 or 3 AZ requirement`)
        }
    }

    private createVpcEndpoints(vpc: IVpc) {
        // Gateway endpoints
        new GatewayVpcEndpoint(this, 'S3VpcEndpoint', {
            service: GatewayVpcEndpointAwsService.S3,
            vpc: vpc,
        });

        // Interface endpoints
        const createInterfaceVpcEndpoint = (service: InterfaceVpcEndpointAwsService) => {
            new InterfaceVpcEndpoint(this, `${service.shortName}VpcEndpoint`, {
                service: service,
                vpc: vpc,
            });
        };

        // General interface endpoints
        const interfaceEndpoints = [
            InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS, // Push Logs from tasks
            InterfaceVpcEndpointAwsService.CLOUDWATCH_MONITORING, // Pull Metrics from Migration Console
            InterfaceVpcEndpointAwsService.ECR_DOCKER, // Pull Images on Startup
            InterfaceVpcEndpointAwsService.ECR, // List Images on Startup
            InterfaceVpcEndpointAwsService.ECS_AGENT, // Task Container Metrics
            InterfaceVpcEndpointAwsService.ECS_TELEMETRY, // Task Container Metrics
            InterfaceVpcEndpointAwsService.ECS, // ECS Task Control
            InterfaceVpcEndpointAwsService.ELASTIC_LOAD_BALANCING, // Control ALB
            InterfaceVpcEndpointAwsService.SECRETS_MANAGER, // Cluster Password Secret
            InterfaceVpcEndpointAwsService.SSM_MESSAGES, // Session Manager
            InterfaceVpcEndpointAwsService.SSM, // Parameter Store
            InterfaceVpcEndpointAwsService.XRAY, // X-Ray Traces
            isStackInGovCloud(this) ?
                InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM_FIPS : // EFS Control Plane GovCloud
                InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM, // EFS Control Plane

        ];
        interfaceEndpoints.forEach(service => createInterfaceVpcEndpoint(service));
    }

    constructor(scope: Construct, id: string, props: NetworkStackProps) {
        super(scope, id, props);
        let vpc: IVpc;

        // Retrieve original deployment VPC for addon deployments
        if (props.addOnMigrationDeployId) {
            const vpcId = StringParameter.valueFromLookup(this,
                getMigrationStringParameterName({
                    stage: props.stage,
                    defaultDeployId: props.defaultDeployId,
                    parameter: MigrationSSMParameter.VPC_ID
                })
            )
            vpc = Vpc.fromLookup(this, 'domainVPC', {
                vpcId
            });
        }
        // Retrieve existing VPC
        else if (props.vpcId) {
            vpc = Vpc.fromLookup(this, 'domainVPC', {
                vpcId: props.vpcId,
            });
        }
        // Create new VPC
        else {
            const zoneCount = props.vpcAZCount
            // Either 2 or 3 AZ count must be used
            if (zoneCount && zoneCount !== 2 && zoneCount !== 3) {
                throw new Error(`Required vpcAZCount is 2 or 3 but received: ${zoneCount}`)
            }
            vpc = new Vpc(this, 'domainVPC', {
                // IP space should be customized for use cases that have specific IP range needs
                ipAddresses: IpAddresses.cidr('10.0.0.0/16'),
                maxAzs: zoneCount ?? 2,
                subnetConfiguration: [
                    // Outbound internet access for private subnets require a NAT Gateway which must live in
                    // a public subnet
                    {
                        name: 'public-subnet',
                        subnetType: SubnetType.PUBLIC,
                        cidrMask: 24,
                    },
                    // Nodes will live in these subnets
                    {
                        name: 'private-subnet',
                        subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                        cidrMask: 24,
                    },
                ],
                natGateways: 0,
            });
            // Only create interface endpoints if VPC not imported
            this.createVpcEndpoints(vpc);
        }
        this.validateVPC(vpc)
        this.vpcDetails = new VpcDetails(vpc, props.vpcSubnetIds);

        if(!props.addOnMigrationDeployId) {
            createMigrationStringParameter(this, vpc.vpcId, {
                ...props,
                parameter: MigrationSSMParameter.VPC_ID
            });
        }

        const needAlb = props.captureProxyServiceEnabled ||
            props.elasticsearchServiceEnabled ||
            props.captureProxyESServiceEnabled ||
            props.targetClusterProxyServiceEnabled;

        if(needAlb) {
            // Create the ALB with the strongest TLS 1.3 security policy
            const alb = new ApplicationLoadBalancer(this, 'ALB', {
                vpc: vpc,
                internetFacing: false,
                http2Enabled: false,
                loadBalancerName: `MigrationAssistant-${props.stage}`
            });

            const route53 = new HostedZone(this, 'ALBHostedZone', {
                zoneName: `alb.migration.${props.stage}.local`,
                vpcs: [vpc]
            });

            const createALBListenerUrlParameter = (port: number, parameter: MigrationSSMParameter): void => {
                createMigrationStringParameter(this, `https://${alb.loadBalancerDnsName}:${port}`, {
                    ...props,
                    parameter: parameter
                });
            };

            const createALBListenerUrlParameterAlias = (port: number, parameter: MigrationSSMParameter): void => {
                createMigrationStringParameter(this, `https://${albDnsRecord.domainName}:${port}`, {
                    ...props,
                    parameter: parameter
                });
            };

            const albDnsRecord = new ARecord(this, 'albDnsRecord', {
                zone: route53,
                target: RecordTarget.fromAlias(new LoadBalancerTarget(alb)),
            });

            let cert: ICertificate;
            if (props.albAcmCertArn) {
                cert = Certificate.fromCertificateArn(this, 'ALBListenerCert', props.albAcmCertArn);
            } else {
                cert = new AcmCertificateImporter(this, 'ALBListenerCertImport', props.stage).acmCert;
            }

            // Setup when deploying elasticsearch source on ECS
            if (props.elasticsearchServiceEnabled || props.captureProxyESServiceEnabled) {
                const targetPort = props.captureProxyESServiceEnabled ? 19200 : 9200
                this.albSourceClusterTG = this.createSecureTargetGroup('ALBSourceCluster', props.stage, targetPort, vpc);
                this.createSecureListener('SourceCluster', 9999, alb, cert, this.albSourceClusterTG);
                createALBListenerUrlParameter(9999, MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT);
            }

            // Setup when deploying capture proxy in ECS
            if (props.captureProxyServiceEnabled || props.captureProxyESServiceEnabled) {
                this.albSourceProxyTG = this.createSecureTargetGroup('ALBSourceProxy', props.stage, 9200, vpc);
                this.createSecureListener('SourceProxy', 9201, alb, cert, this.albSourceProxyTG);
                createALBListenerUrlParameter(9201, MigrationSSMParameter.SOURCE_PROXY_URL);
                createALBListenerUrlParameterAlias(9201, MigrationSSMParameter.SOURCE_PROXY_URL_ALIAS);
            }

            // Setup when deploying target cluster proxy in ECS
            if (props.targetClusterProxyServiceEnabled) {
                this.albTargetProxyTG = this.createSecureTargetGroup('ALBTargetProxy', props.stage, 9200, vpc);
                this.createSecureListener('TargetProxy', 9202, alb, cert, this.albTargetProxyTG);
                createALBListenerUrlParameter(9202, MigrationSSMParameter.TARGET_PROXY_URL);
                createALBListenerUrlParameterAlias(9202, MigrationSSMParameter.TARGET_PROXY_URL_ALIAS);
            }

            // Setup ALB weighted listener when both source and target proxies are enabled
            if (this.albSourceProxyTG && this.albTargetProxyTG) {
                const albMigrationListener = this.createSecureListener('ALBMigrationListener', 443, alb, cert);
                albMigrationListener.addAction("default", {
                    action: ListenerAction.weightedForward([
                        {targetGroup: this.albSourceProxyTG, weight: 1},
                        {targetGroup: this.albTargetProxyTG, weight: 0}
                    ])
                });
                createALBListenerUrlParameter(443, MigrationSSMParameter.MIGRATION_LISTENER_URL);
                createALBListenerUrlParameterAlias(443, MigrationSSMParameter.MIGRATION_LISTENER_URL_ALIAS);
            }
        }

        // Create Source SSM Parameter
        if (props.sourceClusterEndpoint) {
            createMigrationStringParameter(this, props.sourceClusterEndpoint, {
                ...props,
                parameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT
            });
        } else if (!props.sourceClusterDisabled && !this.albSourceClusterTG) {
            throw new Error(`Capture Proxy ESService, Elasticsearch Service, or SourceClusterEndpoint must be enabled, unless the source cluster is disabled.`);
        }

        if (!props.addOnMigrationDeployId) {
            // Create a default SG which only allows members of this SG to access the Domain endpoints
            const defaultSecurityGroup = new SecurityGroup(this, 'osClusterAccessSG', {
                vpc: vpc,
                allowAllOutbound: false,
                allowAllIpv6Outbound: false,
            });
            defaultSecurityGroup.addIngressRule(defaultSecurityGroup, Port.allTraffic());

            createMigrationStringParameter(this, defaultSecurityGroup.securityGroupId, {
                ...props,
                parameter: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID
            });

            if (props.targetClusterEndpoint) {
                const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId;
                createMigrationStringParameter(this, props.targetClusterEndpoint, {
                    stage: props.stage,
                    defaultDeployId: deployId,
                    parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT
                });
                // This is a somewhat surprsing place for this non-network related set of parameters, but it pairs well with
                // the OS_CLUSTER_ENDPOINT parameter and is helpful to ensure it happens. This probably isn't a long-term place
                // for it, but is helpful for the time being.
                if (props.targetClusterUsername && props.targetClusterPasswordSecretArn) {
                    createMigrationStringParameter(this,
                        `${props.targetClusterUsername} ${props.targetClusterPasswordSecretArn}`, {
                        parameter: MigrationSSMParameter.OS_USER_AND_SECRET_ARN,
                        defaultDeployId: deployId,
                        stage: props.stage,
                    });
                }
            }
        }
    }

    getSecureListenerSslPolicy() {
        return isStackInGovCloud(this) ? SslPolicy.FIPS_TLS13_12_EXT2 : SslPolicy.RECOMMENDED_TLS
    }

    createSecureListener(serviceName: string, listeningPort: number, alb: IApplicationLoadBalancer, cert: ICertificate, albTargetGroup?: IApplicationTargetGroup) {
        return new ApplicationListener(this, `${serviceName}ALBListener`, {
            loadBalancer: alb,
            port: listeningPort,
            protocol: ApplicationProtocol.HTTPS,
            certificates: [cert],
            defaultTargetGroups: albTargetGroup ? [albTargetGroup] : undefined,
            sslPolicy: this.getSecureListenerSslPolicy()
        });
    }

    createSecureTargetGroup(serviceName: string, stage: string, containerPort: number, vpc: IVpc) {
        return new ApplicationTargetGroup(this, `${serviceName}TG`, {
            targetGroupName: `${serviceName}-${stage}-TG`,
            protocol: ApplicationProtocol.HTTPS,
            protocolVersion: ApplicationProtocolVersion.HTTP1,
            port: containerPort,
            vpc: vpc,
            // Cluster may reject requests with no authentication
            healthCheck: {
                path: "/",
                healthyHttpCodes: "200-499"
            }
        });
    }
}
