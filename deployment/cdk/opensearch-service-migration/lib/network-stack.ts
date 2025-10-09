import {
    GatewayVpcEndpoint,
    GatewayVpcEndpointAwsService,
    InterfaceVpcEndpoint,
    InterfaceVpcEndpointAwsService,
    IpAddresses,
    IVpc,
    Port,
    SecurityGroup,
    SubnetFilter,
    SubnetSelection,
    SubnetType,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {StackPropsExt} from "./stack-composer";
import {
    ApplicationListener,
    ApplicationLoadBalancer,
    ApplicationProtocol,
    ApplicationProtocolVersion,
    ApplicationTargetGroup,
    IApplicationLoadBalancer,
    IApplicationTargetGroup,
    ListenerAction,
    SslPolicy
} from "aws-cdk-lib/aws-elasticloadbalancingv2";
import {Certificate, ICertificate} from "aws-cdk-lib/aws-certificatemanager";
import {ARecord, HostedZone, RecordTarget} from "aws-cdk-lib/aws-route53";
import {LoadBalancerTarget} from "aws-cdk-lib/aws-route53-targets";
import {AcmCertificateImporter} from "./service-stacks/acm-cert-importer";
import {Stack} from "aws-cdk-lib";
import {
    ClusterType,
    createMigrationStringParameter,
    getMigrationStringParameterName,
    isStackInGovCloud,
    MigrationSSMParameter,
    parseClusterDefinition
} from "./common-utilities";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {CdkLogger} from "./cdk-logger";
import {StreamingSourceType} from "./streaming-source-type";
import {ClusterYaml} from "./migration-services-yaml";

export interface NetworkStackProps extends StackPropsExt {
    readonly vpcId?: string;
    readonly vpcSubnetIds?: string[];
    readonly vpcAZCount?: number;
    readonly streamingSourceType: StreamingSourceType
    readonly elasticsearchServiceEnabled?: boolean;
    readonly captureProxyServiceEnabled?: boolean;
    readonly targetClusterProxyServiceEnabled?: boolean;
    readonly sourceClusterDisabled?: boolean;
    readonly albAcmCertArn?: string;
    readonly managedServiceSourceSnapshotEnabled: boolean;
    readonly sourceClusterDefinition?: Record<string, unknown>;
    readonly targetClusterDefinition?: Record<string, unknown>;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    readonly env?: Record<string, any>;
}

export class VpcDetails {
    public readonly subnetSelection: SubnetSelection;
    public readonly azCount: number;
    public readonly vpc: IVpc;

    /**
     *  This function returns the SubnetType of a list of subnet ids, and throws an error if the subnets do not exist
     *  in the VPC or are of different subnet types.
     *
     *  There is a limitation on the vpc.selectSubnets() call which requires the SubnetType to be provided or else an
     *  empty list will be returned if public subnets are provided, thus this function tries different subnet types if
     *  unable to select the provided subnetIds
     */
    private getSubnetTypeOfProvidedSubnets(vpc: IVpc, subnetIds: string[]): SubnetType {
        const subnetsTypeList = []
        if (vpc.privateSubnets.length > 0) {
            subnetsTypeList.push(SubnetType.PRIVATE_WITH_EGRESS)
        }
        if (vpc.publicSubnets.length > 0) {
            subnetsTypeList.push(SubnetType.PUBLIC)
        }
        if (vpc.isolatedSubnets.length > 0) {
            subnetsTypeList.push(SubnetType.PRIVATE_ISOLATED)
        }
        for (const subnetType of subnetsTypeList) {
            const subnets = vpc.selectSubnets({
                subnetType: subnetType,
                subnetFilters: [SubnetFilter.byIds(subnetIds)]
            })
            if (subnets.subnetIds.length == subnetIds.length) {
                return subnetType
            }
        }
        throw new Error(`Unable to find subnet ids: [${subnetIds}] in VPC: ${vpc.vpcId}. Please ensure all subnet ids exist and are of the same subnet type`)
    }

    private validateProvidedSubnetIds(vpc: IVpc, vpcSubnetIds: string[], azCount: number) {
        if (vpcSubnetIds.length != azCount) {
            throw new Error(`The number of provided subnets (${vpcSubnetIds.length}), must match the AZ count of ${azCount}. The setting can be specified with the 'vpcAZCount' option`)
        }
        const subnetType = this.getSubnetTypeOfProvidedSubnets(vpc, vpcSubnetIds);
        const uniqueAzSubnets = vpc.selectSubnets({
            onePerAz: true,
            subnetType: subnetType,
            subnetFilters: [SubnetFilter.byIds(vpcSubnetIds)]
        })
        if (uniqueAzSubnets.subnetIds.length != vpcSubnetIds.length) {
            throw new Error(`Not all subnet ids provided: [${vpcSubnetIds}] were in a unique AZ`)
        }
        return uniqueAzSubnets
    }
    
    constructor(vpc: IVpc, azCount: number, vpcSubnetIds?: string[]) {
        this.vpc = vpc
        this.azCount = azCount
        CdkLogger.info(`Detected VPC with ${vpc.privateSubnets.length} private subnets, ${vpc.publicSubnets.length} public subnets, and ${vpc.isolatedSubnets.length} isolated subnets`)

        // Skip VPC validations for first synthesis stage which hasn't yet loaded in the VPC details from lookup
        if (vpc.vpcId == "vpc-12345") {
            return
        }

        if (vpcSubnetIds) {
            this.subnetSelection = this.validateProvidedSubnetIds(vpc, vpcSubnetIds, azCount)
        } else {
            if (vpc.privateSubnets.length < 1) {
                throw new Error(`No private subnets detected in VPC: ${vpc.vpcId}. Alternatively subnets can be manually specified with the 'vpcSubnetIds' context option`)
            }
            const uniqueAzPrivateSubnets = vpc.selectSubnets({
                onePerAz: true,
                subnetType: SubnetType.PRIVATE_WITH_EGRESS
            })
            if (uniqueAzPrivateSubnets.subnetIds.length < azCount) {
                throw new Error(`Not enough AZs (${azCount} unique AZs detected) used for private subnets to meet the ${azCount} AZ requirement. Alternatively subnets can be manually specified with the 'vpcSubnetIds' option and the AZ requirement set with the 'vpcAZCount' option`)
            }
            const desiredSubnetIds = uniqueAzPrivateSubnets.subnetIds
                .sort((a, b) => a.localeCompare(b))
                .slice(0, azCount);
            this.subnetSelection = vpc.selectSubnets({
                subnetFilters: [
                    SubnetFilter.byIds(desiredSubnetIds)
                ]
            })
        }
    }
}

export class NetworkStack extends Stack {
    public readonly albSourceProxyTG: IApplicationTargetGroup;
    public readonly albTargetProxyTG: IApplicationTargetGroup;
    public readonly albSourceClusterTG: IApplicationTargetGroup;
    public readonly sourceClusterYaml?: ClusterYaml;
    public readonly targetClusterYaml?: ClusterYaml;
    public readonly vpcDetails: VpcDetails;

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
        const zoneCount = props.vpcAZCount ?? 2
        const deployId = props.addOnMigrationDeployId ?? props.defaultDeployId;

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
            vpc = new Vpc(this, 'domainVPC', {
                // IP space should be customized for use cases that have specific IP range needs
                ipAddresses: IpAddresses.cidr('10.0.0.0/16'),
                maxAzs: zoneCount,
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
        if(!props.addOnMigrationDeployId) {
            createMigrationStringParameter(this, vpc.vpcId, {
                ...props,
                parameter: MigrationSSMParameter.VPC_ID
            });
        }

        const needAlb = props.captureProxyServiceEnabled ||
            props.elasticsearchServiceEnabled ||
            props.targetClusterProxyServiceEnabled;

        // Check that AZ requirements are met
        // MSK requirement: Exactly two or three subnets with each subnet in a different Availability Zone
        // ALB requirement: At least two subnets in two different Availability Zones
        if ((needAlb || props.streamingSourceType == StreamingSourceType.AWS_MSK) && zoneCount !== 2 && zoneCount !== 3) {
            throw new Error(`Capture and Replay migrations, as well as migrations with a capture proxy or target proxy, have a requirement that 2 or 3 AZs must be used, however, the 'vpcAZCount' context option is set to: ${zoneCount}`)
        }

        this.vpcDetails = new VpcDetails(vpc, zoneCount, props.vpcSubnetIds);

        this.sourceClusterYaml = props.sourceClusterDefinition
            ? parseClusterDefinition(props.sourceClusterDefinition, ClusterType.SOURCE, this, props.stage, deployId)
            : undefined
        this.targetClusterYaml = props.targetClusterDefinition
            ? parseClusterDefinition(props.targetClusterDefinition, ClusterType.TARGET, this, props.stage, deployId)
            : undefined
        if (props.managedServiceSourceSnapshotEnabled && !this.sourceClusterYaml?.auth.sigv4) {
            throw new Error("A managed service source snapshot is only compatible with sigv4 authentication. If you would like to proceed" +
                " please disable `managedServiceSourceSnapshotEnabled` and provide your own snapshot of the source cluster.")
        }

        if (needAlb) {
            // Create the ALB with the strongest TLS 1.3 security policy
            const alb = new ApplicationLoadBalancer(this, 'ALB', {
                vpc: vpc,
                vpcSubnets: this.vpcDetails.subnetSelection,
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
            if (props.elasticsearchServiceEnabled) {
                this.albSourceClusterTG = this.createSecureTargetGroup('ALBSourceCluster', props.stage, 9200, vpc);
                this.createSecureListener('SourceCluster', 9999, alb, cert, this.albSourceClusterTG);
                createALBListenerUrlParameter(9999, MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT);
            }

            // Setup when deploying capture proxy in ECS
            if (props.captureProxyServiceEnabled) {
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
        if (this.sourceClusterYaml?.endpoint) {
            createMigrationStringParameter(this, this.sourceClusterYaml.endpoint, {
                ...props,
                parameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT
            });
        } else if (!props.sourceClusterDisabled && !this.albSourceClusterTG) {
            throw new Error(`The sourceCluster definition must be provided, or disabled with a definition similar to "sourceCluster":{"disabled":true}`);
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

            if (this.targetClusterYaml?.endpoint) {
                createMigrationStringParameter(this, this.targetClusterYaml.endpoint, {
                    stage: props.stage,
                    defaultDeployId: deployId,
                    parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT
                });
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
