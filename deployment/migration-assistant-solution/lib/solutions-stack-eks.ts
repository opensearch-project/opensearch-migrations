import * as cdk from 'aws-cdk-lib';
import {
    Aws,
    CfnCondition,
    CfnMapping, CfnOutput,
    CfnParameter,
    Fn,
    Stack,
    StackProps,
    Tags
} from 'aws-cdk-lib';
import {Construct} from 'constructs';
import {
    GatewayVpcEndpoint,
    GatewayVpcEndpointAwsService,
    IVpc,
    InterfaceVpcEndpoint,
    InterfaceVpcEndpointAwsService,
    IpAddresses,
    IpProtocol,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {EKSInfra} from "./eks-infra";
import {
    addParameterLabel,
    applyAppRegistry,
    generateExportString,
    getVpcEndpointForEFS,
    ParameterLabel,
    buildTemplateDescription
} from "./common-utils";

export interface SolutionsInfrastructureStackEKSProps extends StackProps {
    readonly solutionId: string;
    readonly solutionName: string;
    readonly solutionVersion: string;
    readonly codeBucket: string;
    readonly createVPC: boolean;
    readonly stackNameSuffix?: string;
}

function importVPC(stack: Stack, vpdIdParameter: CfnParameter): IVpc {
    return Vpc.fromVpcAttributes(stack, 'ImportedVPC', {
        vpcId: vpdIdParameter.valueAsString,
        availabilityZones: Fn.getAzs()
    });
}

export class SolutionsInfrastructureEKSStack extends Stack {

    constructor(scope: Construct, id: string, props: SolutionsInfrastructureStackEKSProps) {
        const finalId = props.stackNameSuffix ? `${id}-${props.stackNameSuffix}` : id
        super(scope, finalId, props);

        // Distinct description for EKS create vs import
        this.templateOptions.description = buildTemplateDescription(
            props.createVPC ? 'EKS_VPC_CREATE' : 'EKS_VPC_IMPORT'
        );

        this.templateOptions.templateFormatVersion = '2010-09-09';
        new CfnMapping(this, 'Solution', {
            mapping: {
                Config: {
                    CodeVersion: props.solutionVersion,
                    KeyPrefix: `${props.solutionName}/${props.solutionVersion}`,
                    S3Bucket: props.codeBucket,
                    SendAnonymousUsage: 'No',
                    SolutionId: props.solutionId
                }
            },
            lazy: false,
        });

        const importedVPCParameters: string[] = [];
        const additionalParameters: string[] = [];
        const parameterLabels: Record<string, ParameterLabel> = {};
        const stageParameter = new CfnParameter(this, 'Stage', {
            type: 'String',
            description: 'Specify the stage identifier which will be used in naming resources, e.g. dev,gamma,wave1',
            default: 'dev',
        });
        additionalParameters.push(stageParameter.logicalId)

        const stackMarker = `${stageParameter.valueAsString}-${Aws.REGION}`;
        const appRegistryAppARN = applyAppRegistry(this, stackMarker, props)

        const solutionsUserAgent = `AwsSolution/${props.solutionId}/${props.solutionVersion}`

        let vpc: IVpc;
        let vpcSubnetIds: string[] = []
        if (props.createVPC) {
            vpc = new Vpc(this, `Vpc`, {
                // Using 10.212.0.0/16 to avoid default VPC CIDR range conflicts when using VPC peering
                ipAddresses: IpAddresses.cidr('10.212.0.0/16'),
                ipProtocol: IpProtocol.DUAL_STACK,
                vpcName: `migration-assistant-vpc-${stageParameter.valueAsString}`,
                maxAzs: 2
            });

            vpc.publicSubnets.forEach((subnet, index) => {
                Tags.of(subnet)
                    .add("Name", `migration-assistant-public-subnet-${index + 1}-${stageParameter.valueAsString}`);
            });
            vpc.privateSubnets.forEach((subnet, index) => {
                Tags.of(subnet)
                    .add("Name", `migration-assistant-private-subnet-${index + 1}-${stageParameter.valueAsString}`);
            });

            // S3 used for storage and retrieval of snapshot data for backfills
            new GatewayVpcEndpoint(this, 'S3VpcEndpoint', {
                service: GatewayVpcEndpointAwsService.S3,
                vpc: vpc,
                subnets: [{ subnets: vpc.privateSubnets }],
            });

            const serviceEndpoints = [
                // Logs and disk usage scales based on total data transfer
                InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
                getVpcEndpointForEFS(this),

                // Elastic container registry is used for all images in the solution
                InterfaceVpcEndpointAwsService.ECR,
                InterfaceVpcEndpointAwsService.ECR_DOCKER,
            ];

            serviceEndpoints.forEach(service => {
                new InterfaceVpcEndpoint(this, `${service.shortName}VpcEndpoint`, {
                    service,
                    vpc: vpc,
                });
            })
        }
        else {
            const vpcIdParameter = new CfnParameter(this, 'VPCId', {
                type: 'AWS::EC2::VPC::Id',
                description: '(Required) Select a VPC, we recommend choosing the VPC of the target cluster.'
            });
            addParameterLabel(parameterLabels, vpcIdParameter, "VPC")

            const subnetIdsParameter = new CfnParameter(this, 'VPCSubnetIds', {
                type: 'List<AWS::EC2::Subnet::Id>',
                description: '(Required) Select at least two private/public subnets, each in a unique Availability Zone.'
            });
            addParameterLabel(parameterLabels, subnetIdsParameter, "Subnets")
            vpcSubnetIds = subnetIdsParameter.valueAsList

            importedVPCParameters.push(vpcIdParameter.logicalId, subnetIdsParameter.logicalId)
            vpc = importVPC(this, vpcIdParameter);

            // Optional VPC endpoint creation for imported VPCs with private subnets
            const vpcEndpointParameters: string[] = [];

            const createS3EndpointParam = new CfnParameter(this, 'CreateS3Endpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create an S3 gateway VPC endpoint (required for ECR image layer pulls in private subnets).'
            });
            addParameterLabel(parameterLabels, createS3EndpointParam, "Create S3 VPC Endpoint")
            vpcEndpointParameters.push(createS3EndpointParam.logicalId)

            const createECREndpointParam = new CfnParameter(this, 'CreateECREndpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create an ECR API interface VPC endpoint (required for private ECR image pulls).'
            });
            addParameterLabel(parameterLabels, createECREndpointParam, "Create ECR API VPC Endpoint")
            vpcEndpointParameters.push(createECREndpointParam.logicalId)

            const createECRDockerEndpointParam = new CfnParameter(this, 'CreateECRDockerEndpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create an ECR Docker interface VPC endpoint (required for private ECR image pulls).'
            });
            addParameterLabel(parameterLabels, createECRDockerEndpointParam, "Create ECR Docker VPC Endpoint")
            vpcEndpointParameters.push(createECRDockerEndpointParam.logicalId)

            const createCWLogsEndpointParam = new CfnParameter(this, 'CreateCloudWatchLogsEndpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create a CloudWatch Logs interface VPC endpoint.'
            });
            addParameterLabel(parameterLabels, createCWLogsEndpointParam, "Create CloudWatch Logs VPC Endpoint")
            vpcEndpointParameters.push(createCWLogsEndpointParam.logicalId)

            const createEFSEndpointParam = new CfnParameter(this, 'CreateEFSEndpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create an EFS interface VPC endpoint.'
            });
            addParameterLabel(parameterLabels, createEFSEndpointParam, "Create EFS VPC Endpoint")
            vpcEndpointParameters.push(createEFSEndpointParam.logicalId)

            const createSTSEndpointParam = new CfnParameter(this, 'CreateSTSEndpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create an STS interface VPC endpoint (required for pod identity on isolated subnets).'
            });
            addParameterLabel(parameterLabels, createSTSEndpointParam, "Create STS VPC Endpoint")
            vpcEndpointParameters.push(createSTSEndpointParam.logicalId)

            const createEKSAuthEndpointParam = new CfnParameter(this, 'CreateEKSAuthEndpoint', {
                type: 'String',
                allowedValues: ['true', 'false'],
                default: 'false',
                description: 'Create an EKS Auth interface VPC endpoint (required for pod identity on isolated subnets).'
            });
            addParameterLabel(parameterLabels, createEKSAuthEndpointParam, "Create EKS Auth VPC Endpoint")
            vpcEndpointParameters.push(createEKSAuthEndpointParam.logicalId)

            // Conditionally create endpoints
            // Security group for interface VPC endpoints — allows HTTPS from the VPC CIDR
            const vpceSecurityGroup = new cdk.CfnResource(this, 'VpcEndpointSecurityGroup', {
                type: 'AWS::EC2::SecurityGroup',
                properties: {
                    GroupDescription: 'Allow HTTPS from VPC CIDR for VPC endpoints',
                    VpcId: vpc.vpcId,
                    SecurityGroupIngress: [{
                        IpProtocol: 'tcp',
                        FromPort: 443,
                        ToPort: 443,
                        CidrIp: '0.0.0.0/0',
                        Description: 'HTTPS for VPC endpoints'
                    }]
                }
            });
            // Only create the SG if any endpoint is being created
            const anyEndpointCondition = new CfnCondition(this, 'AnyEndpointCondition', {
                expression: Fn.conditionOr(
                    Fn.conditionEquals(createS3EndpointParam, 'true'),
                    Fn.conditionEquals(createECREndpointParam, 'true'),
                    Fn.conditionEquals(createECRDockerEndpointParam, 'true'),
                    Fn.conditionEquals(createCWLogsEndpointParam, 'true'),
                    Fn.conditionEquals(createEFSEndpointParam, 'true'),
                    Fn.conditionEquals(createSTSEndpointParam, 'true'),
                    Fn.conditionEquals(createEKSAuthEndpointParam, 'true'),
                )
            });
            vpceSecurityGroup.cfnOptions.condition = anyEndpointCondition;

            const s3Condition = new CfnCondition(this, 'CreateS3EndpointCondition', {
                expression: Fn.conditionEquals(createS3EndpointParam, 'true')
            });

            const s3EndpointRouteTableIdsParam = new CfnParameter(this, 'S3EndpointRouteTableIds', {
                type: 'CommaDelimitedList',
                default: '',
                description: 'Route table IDs for S3 gateway endpoint association (resolved from subnet IDs by the bootstrap script).'
            });
            addParameterLabel(parameterLabels, s3EndpointRouteTableIdsParam, "S3 Endpoint Route Table IDs")
            vpcEndpointParameters.push(s3EndpointRouteTableIdsParam.logicalId)

            // S3 gateway endpoint — RouteTableIds required so ECR layer downloads route through the gateway
            const s3Endpoint = new cdk.CfnResource(this, 'S3VpcEndpoint', {
                type: 'AWS::EC2::VPCEndpoint',
                properties: {
                    ServiceName: `com.amazonaws.${this.region}.s3`,
                    VpcId: vpc.vpcId,
                    VpcEndpointType: 'Gateway',
                    RouteTableIds: s3EndpointRouteTableIdsParam.valueAsList,
                }
            });
            s3Endpoint.cfnOptions.condition = s3Condition;

            const endpointConfigs: {param: CfnParameter, serviceSuffix: string, name: string}[] = [
                { param: createECREndpointParam, serviceSuffix: 'ecr.api', name: 'ECR' },
                { param: createECRDockerEndpointParam, serviceSuffix: 'ecr.dkr', name: 'ECRDocker' },
                { param: createCWLogsEndpointParam, serviceSuffix: 'logs', name: 'CloudWatchLogs' },
                { param: createEFSEndpointParam, serviceSuffix: 'elasticfilesystem', name: 'EFS' },
                { param: createSTSEndpointParam, serviceSuffix: 'sts', name: 'STS' },
                { param: createEKSAuthEndpointParam, serviceSuffix: 'eks-auth', name: 'EKSAuth' },
            ];

            for (const config of endpointConfigs) {
                const condition = new CfnCondition(this, `Create${config.name}EndpointCondition`, {
                    expression: Fn.conditionEquals(config.param, 'true')
                });
                const endpoint = new cdk.CfnResource(this, `${config.name}VpcEndpoint`, {
                    type: 'AWS::EC2::VPCEndpoint',
                    properties: {
                        ServiceName: `com.amazonaws.${this.region}.${config.serviceSuffix}`,
                        VpcId: vpc.vpcId,
                        VpcEndpointType: 'Interface',
                        PrivateDnsEnabled: true,
                        SubnetIds: vpcSubnetIds,
                        SecurityGroupIds: [vpceSecurityGroup.ref],
                    }
                });
                endpoint.cfnOptions.condition = condition;
            }

            importedVPCParameters.push(...vpcEndpointParameters)
        }

        const eksClusterName = `migration-eks-cluster-${stackMarker}`
        const eksInfra = new EKSInfra(this, 'EKSInfra', {
            vpc,
            vpcSubnetIds,
            clusterName: eksClusterName,
            stackName: Fn.ref('AWS::StackName'),
            ecrRepoName: `migration-ecr-${stackMarker}`
        });

        const exportString = generateExportString({
            "MIGRATIONS_APP_REGISTRY_ARN": appRegistryAppARN,
            "MIGRATIONS_USER_AGENT": solutionsUserAgent,
            "MIGRATIONS_EKS_CLUSTER_NAME": eksClusterName,
            "MIGRATIONS_ECR_REGISTRY": `${eksInfra.ecrRepo.registryUri}/${eksInfra.ecrRepo.repositoryName}`,
            "AWS_ACCOUNT": this.account,
            "AWS_CFN_REGION": this.region,
            "VPC_ID": vpc.vpcId,
            "EKS_CLUSTER_SECURITY_GROUP": eksInfra.cluster.clusterSecurityGroupId,
            "SNAPSHOT_ROLE": eksInfra.snapshotRole.roleArn.toString(),
            "STAGE": stageParameter.valueAsString
        })
        new CfnOutput(this, 'MigrationsExportString', {
            value: exportString,
            description: 'Export string for Migration resources created from this deployment',
            exportName: `MigrationsExportString-${stackMarker}`,
        });

        const parameterGroups = [];
        if (importedVPCParameters.length > 0) {
            parameterGroups.push({
                Label: { default: "Imported VPC parameters" },
                Parameters: importedVPCParameters
            });
        }
        parameterGroups.push({
            Label: { default: "Additional parameters" },
            Parameters: additionalParameters
        });

        this.templateOptions.metadata = {
            'AWS::CloudFormation::Interface': {
                ParameterGroups: parameterGroups,
                ParameterLabels: parameterLabels
            }
        }
    }
}
