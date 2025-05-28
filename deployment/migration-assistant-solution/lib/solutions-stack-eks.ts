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
import {Application, AttributeGroup} from "@aws-cdk/aws-servicecatalogappregistry-alpha";
import {EKSInfra} from "./eks-infra";

export interface SolutionsInfrastructureStackEKSProps extends StackProps {
    readonly solutionId: string;
    readonly solutionName: string;
    readonly solutionVersion: string;
    readonly codeBucket: string;
    readonly createVPC: boolean;
    readonly stackNameSuffix?: string;
}

interface ParameterLabel {
    default: string;
}

function applyAppRegistry(stack: Stack, stage: string, infraProps: SolutionsInfrastructureStackEKSProps): string {
    const application = new Application(stack, "AppRegistry", {
        applicationName: Fn.join("-", [
            infraProps.solutionName,
            Aws.REGION,
            Aws.ACCOUNT_ID,
            stage
        ]),
        description: `Service Catalog application to track and manage all your resources for the solution ${infraProps.solutionName}`,
    });
    application.associateApplicationWithStack(stack);
    Tags.of(application).add("Solutions:SolutionID", infraProps.solutionId);
    Tags.of(application).add("Solutions:SolutionName", infraProps.solutionName);
    Tags.of(application).add("Solutions:SolutionVersion", infraProps.solutionVersion);
    Tags.of(application).add("Solutions:ApplicationType", "AWS-Solutions");

    const attributeGroup = new AttributeGroup(
        stack,
        "DefaultApplicationAttributes",
        {
            attributeGroupName: Fn.join("-", [
                Aws.REGION,
                stage,
                "attributes"
            ]),
            description: "Attribute group for solution information",
            attributes: {
                applicationType: "AWS-Solutions",
                version: infraProps.solutionVersion,
                solutionID: infraProps.solutionId,
                solutionName: infraProps.solutionName,
            },
        }
    );
    attributeGroup.associateWith(application)
    return application.applicationArn
}

function addParameterLabel(labels: Record<string, ParameterLabel>, parameter: CfnParameter, labelName: string) {
    labels[parameter.logicalId] = {"default": labelName}
}

function importVPC(stack: Stack, vpdIdParameter: CfnParameter, privateSubnetIdsParameter: CfnParameter): IVpc {
    // TODO add support for import VPC scenario with VPC validations
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const privateSubnetIds = privateSubnetIdsParameter.valueAsList
    return Vpc.fromLookup(stack, 'ImportedVPC', {
        vpcId: vpdIdParameter.valueAsString,
    });
}

function generateExportString(exports:  Record<string, string>): string {
    return Object.entries(exports)
        .map(([key, value]) => `export ${key}=${value}`)
        .join("; ");
}

function getVpcEndpointForEFS(stack: Stack): InterfaceVpcEndpointAwsService {
    const isGovRegion = stack.region?.startsWith('us-gov-')
    if (isGovRegion) {
        return InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM_FIPS;
    }
    return InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM;
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
                description: 'Select a VPC, we recommend choosing the VPC of the target cluster.'
            });
            addParameterLabel(parameterLabels, vpcIdParameter, "VPC")

            const privateSubnetIdsParameter = new CfnParameter(this, 'VPCPrivateSubnetIds', {
                type: 'List<AWS::EC2::Subnet::Id>',
                description: 'Select Private Subnets in the selected VPC. Please provide two subnets at least, corresponding with the availability zones selected previously.'
            });
            addParameterLabel(parameterLabels, privateSubnetIdsParameter, "Private Subnets")
            importedVPCParameters.push(vpcIdParameter.logicalId, privateSubnetIdsParameter.logicalId)
            vpc = importVPC(this, vpcIdParameter, privateSubnetIdsParameter);
        }

        const eksClusterName = `migration-eks-cluster-${stackMarker}`
        const eksInfra = new EKSInfra(this, 'EKSInfra', {
            vpc,
            clusterName: eksClusterName,
            stackName: Fn.ref('AWS::StackName'),
            ecrRepoName: `migration-ecr-${stackMarker}`
        });

        const exportString = generateExportString({
            "MIGRATIONS_APP_REGISTRY_ARN": appRegistryAppARN,
            "MIGRATIONS_USER_AGENT": solutionsUserAgent,
            "MIGRATIONS_EKS_CLUSTER_NAME": eksClusterName,
            "MIGRATIONS_ECR_REGISTRY": `${eksInfra.ecrRepo.registryUri}/${eksInfra.ecrRepo.repositoryName}`,
            "AWS_CFN_REGION": this.region,
            "VPC_ID": vpc.vpcId,
            "STAGE": stageParameter.valueAsString
        })
        new CfnOutput(this, 'MigrationsExportString', {
            value: exportString,
            description: 'Export string for Migration resources created from this deployment',
            exportName: 'MigrationsExportString',
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
