import {
    Aws,
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
    ParameterLabel
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
            "EKS_CLUSTER_SECURITY_GROUP": eksInfra.cluster.attrClusterSecurityGroupId.toString(),
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
