import {
    IpAddresses, IVpc, Port, SecurityGroup,
    SubnetType,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {StackPropsExt} from "./stack-composer";
import { ApplicationListener, ApplicationLoadBalancer, ApplicationProtocol, ApplicationProtocolVersion, ApplicationTargetGroup, IApplicationLoadBalancer, IApplicationTargetGroup, ListenerAction, SslPolicy } from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { Certificate, ICertificate } from "aws-cdk-lib/aws-certificatemanager";
import { ARecord, HostedZone, RecordTarget } from "aws-cdk-lib/aws-route53";
import { LoadBalancerTarget } from "aws-cdk-lib/aws-route53-targets";
import { AcmCertificateImporter } from "./service-stacks/acm-cert-importer";
import { Stack } from "aws-cdk-lib";
import { createMigrationStringParameter, getMigrationStringParameterName, MigrationSSMParameter } from "./common-utilities";
import { StringParameter } from "aws-cdk-lib/aws-ssm";

export interface NetworkStackProps extends StackPropsExt {
    readonly vpcId?: string;
    readonly vpcAZCount?: number;
    readonly elasticsearchServiceEnabled?: boolean;
    readonly captureProxyServiceEnabled?: boolean;
    readonly targetClusterProxyServiceEnabled?: boolean;
    readonly captureProxyESServiceEnabled?: boolean;
    readonly migrationAPIEnabled?: boolean;
    readonly sourceClusterEndpoint?: string;
    readonly targetClusterEndpoint?: string;
    readonly albAcmCertArn?: string;
    readonly env?: { [key: string]: any };
}

export class NetworkStack extends Stack {
    public readonly vpc: IVpc;
    public readonly albSourceProxyTG: IApplicationTargetGroup;
    public readonly albTargetProxyTG: IApplicationTargetGroup;
    public readonly albSourceClusterTG: IApplicationTargetGroup;
    public readonly albMigrationConsoleTG: IApplicationTargetGroup;

    // Validate a proper url string is provided and return an url string which contains a protocol, host name, and port.
    // If a port is not provided, the default protocol port (e.g. 443, 80) will be explicitly added
    static validateAndReturnFormattedHttpURL(urlString: string) {
        // URL will throw error if the urlString is invalid
        let url = new URL(urlString);
        if (url.protocol !== "http:" && url.protocol !== "https:") {
            throw new Error(`Invalid url protocol for target endpoint: ${urlString} was expecting 'http' or 'https'`)
        }
        if (url.pathname !== "/") {
            throw new Error(`Provided target endpoint: ${urlString} must not contain a path: ${url.pathname}`)
        }
        // URLs that contain the default protocol port (e.g. 443, 80) will not show in the URL toString()
        let formattedUrlString = url.toString()
        if (formattedUrlString.endsWith("/")) {
            formattedUrlString = formattedUrlString.slice(0, -1)
        }
        if (!url.port) {
            if (url.protocol === "http:") {
                formattedUrlString = formattedUrlString.concat(":80")
            }
            else {
                formattedUrlString = formattedUrlString.concat(":443")
            }
        }
        return formattedUrlString
    }

    private validateVPC(vpc: IVpc) {
        let uniqueAzPrivateSubnets: string[] = []
        if (vpc.privateSubnets.length > 0) {
            uniqueAzPrivateSubnets = vpc.selectSubnets({
                subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                onePerAz: true
            }).subnetIds
        }
        console.info(`Detected VPC with ${vpc.privateSubnets.length} private subnets, ${vpc.publicSubnets.length} public subnets, and ${vpc.isolatedSubnets.length} isolated subnets`)
        if (uniqueAzPrivateSubnets.length < 2) {
            throw new Error(`Not enough AZs (${uniqueAzPrivateSubnets.length} unique AZs detected) used for private subnets to meet 2 or 3 AZ requirement`)
        }
    }

    constructor(scope: Construct, id: string, props: NetworkStackProps) {
        super(scope, id, props);

        // Retrieve original deployment VPC for addon deployments
        if (props.addOnMigrationDeployId) {
            const vpcId = StringParameter.valueFromLookup(this,
                getMigrationStringParameterName({
                    stage: props.stage,
                    defaultDeployId: props.defaultDeployId,
                    parameter: MigrationSSMParameter.VPC_ID
                })
            )
            this.vpc = Vpc.fromLookup(this, 'domainVPC', {
                vpcId
            });
        }
        // Retrieve existing VPC
        else if (props.vpcId) {
            this.vpc = Vpc.fromLookup(this, 'domainVPC', {
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
            this.vpc = new Vpc(this, 'domainVPC', {
                // IP space should be customized for use cases that have specific IP range needs
                ipAddresses: IpAddresses.cidr('10.0.0.0/16'),
                maxAzs: zoneCount ? zoneCount : 2,
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
            });
        }
        this.validateVPC(this.vpc)
        if(!props.addOnMigrationDeployId) {
            createMigrationStringParameter(this, this.vpc.vpcId, {
                ...props,
                parameter: MigrationSSMParameter.VPC_ID
            });
        }

        const needAlb = props.captureProxyServiceEnabled ||
            props.elasticsearchServiceEnabled ||
            props.migrationAPIEnabled ||
            props.captureProxyESServiceEnabled ||
            props.targetClusterProxyServiceEnabled;

        if(needAlb) {
            // Create the ALB with the strongest TLS 1.3 security policy
            const alb = new ApplicationLoadBalancer(this, 'ALB', {
                vpc: this.vpc,
                internetFacing: false,
                http2Enabled: false,
                loadBalancerName: `MigrationAssistant-${props.stage}`
            });

            const route53 = new HostedZone(this, 'ALBHostedZone', {
                zoneName: `alb.migration.${props.stage}.local`,
                vpcs: [this.vpc]
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
                this.albSourceClusterTG = this.createSecureTargetGroup('ALBSourceCluster', props.stage, targetPort, this.vpc);
                this.createSecureListener('SourceCluster', 9999, alb, cert, this.albSourceClusterTG);
                createALBListenerUrlParameter(9999, MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT);
            }

            // Setup when deploying migration console api on ecs
            if (props.migrationAPIEnabled) {
                this.albMigrationConsoleTG = this.createSecureTargetGroup('ALBMigrationConsole', props.stage, 8000, this.vpc);
                this.createSecureListener('MigrationConsole', 8000, alb, cert, this.albMigrationConsoleTG);
                createALBListenerUrlParameter(8000, MigrationSSMParameter.MIGRATION_API_URL);
                createALBListenerUrlParameterAlias(8000, MigrationSSMParameter.MIGRATION_API_URL_ALIAS);
            }

            // Setup when deploying capture proxy in ECS
            if (props.captureProxyServiceEnabled || props.captureProxyESServiceEnabled) {
                this.albSourceProxyTG = this.createSecureTargetGroup('ALBSourceProxy', props.stage, 9200, this.vpc);
                this.createSecureListener('SourceProxy', 9201, alb, cert, this.albSourceProxyTG);
                createALBListenerUrlParameter(9201, MigrationSSMParameter.SOURCE_PROXY_URL);
                createALBListenerUrlParameterAlias(9201, MigrationSSMParameter.SOURCE_PROXY_URL_ALIAS);
            }

            // Setup when deploying target cluster proxy in ECS
            if (props.targetClusterProxyServiceEnabled) {
                this.albTargetProxyTG = this.createSecureTargetGroup('ALBTargetProxy', props.stage, 9200, this.vpc);
                this.createSecureListener('TargetProxy', 9202, alb, cert, this.albTargetProxyTG);
                createALBListenerUrlParameter(9202, MigrationSSMParameter.TARGET_PROXY_URL);
                createALBListenerUrlParameterAlias(9202, MigrationSSMParameter.TARGET_PROXY_URL_ALIAS);
            }

            // Setup ALB weighted listener when both source and target proxies are enabled
            if (this.albSourceProxyTG && this.albTargetProxyTG) {
                const albMigrationListener = this.createSecureListener('ALBMigrationListener', 9200, alb, cert);
                albMigrationListener.addAction("default", {
                    action: ListenerAction.weightedForward([
                        {targetGroup: this.albSourceProxyTG, weight: 1},
                        {targetGroup: this.albTargetProxyTG, weight: 0}
                    ])
                });
                createALBListenerUrlParameter(9200, MigrationSSMParameter.MIGRATION_LISTENER_URL);
                createALBListenerUrlParameterAlias(9200, MigrationSSMParameter.MIGRATION_LISTENER_URL_ALIAS);
            }
        }

        // Create Source SSM Parameter
        if (props.sourceClusterEndpoint) {
            createMigrationStringParameter(this, props.sourceClusterEndpoint, {
                ...props,
                parameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT
            });
        } else if (!this.albSourceClusterTG) {
            throw new Error(`Capture Proxy ESService, Elasticsearch Service, or SourceClusterEndpoint must be enabled`);
        }

        if (!props.addOnMigrationDeployId) {
            // Create a default SG which only allows members of this SG to access the Domain endpoints
            const defaultSecurityGroup = new SecurityGroup(this, 'osClusterAccessSG', {
                vpc: this.vpc,
                allowAllOutbound: false,
            });
            defaultSecurityGroup.addIngressRule(defaultSecurityGroup, Port.allTraffic());

            createMigrationStringParameter(this, defaultSecurityGroup.securityGroupId, {
                ...props,
                parameter: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID
            });

            if (props.targetClusterEndpoint) {
                const formattedClusterEndpoint = NetworkStack.validateAndReturnFormattedHttpURL(props.targetClusterEndpoint);
                const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId;
                createMigrationStringParameter(this, formattedClusterEndpoint, {
                    stage: props.stage,
                    defaultDeployId: deployId,
                    parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT
                });
            }
        }
    }

    getSecureListenerSslPolicy() {
        return (this.partition === "aws-us-gov") ? SslPolicy.FIPS_TLS13_12_EXT2 : SslPolicy.RECOMMENDED_TLS
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
            healthCheck: {
                path: "/",
                healthyHttpCodes: "200,401"
            }
        });
    }
}
