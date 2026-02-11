import {
    Aws,
    CfnMapping,
    CfnParameter,
    Fn,
    Stack,
    StackProps,
    Tags
} from 'aws-cdk-lib';
import {Construct} from 'constructs';
import {
    BlockDeviceVolume,
    CloudFormationInit,
    GatewayVpcEndpoint,
    GatewayVpcEndpointAwsService,
    IVpc,
    GenericLinuxImage,
    InitCommand,
    InitElement,
    InitFile,
    Instance,
    InstanceClass,
    InstanceSize,
    InstanceType,
    InterfaceVpcEndpoint,
    InterfaceVpcEndpointAwsService,
    IpAddresses,
    IpProtocol,
    SecurityGroup,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {CfnDocument} from "aws-cdk-lib/aws-ssm";
import { InstanceProfile, ManagedPolicy, Role, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import {
    addParameterLabel,
    applyAppRegistry,
    generateExportString,
    getVpcEndpointForEFS,
    ParameterLabel,
    buildTemplateDescription
} from "./common-utils";

export interface SolutionsInfrastructureStackProps extends StackProps {
    readonly solutionId: string;
    readonly solutionName: string;
    readonly solutionVersion: string;
    readonly codeBucket: string;
    readonly createVPC: boolean;
    readonly stackNameSuffix?: string;
}

function importVPC(stack: Stack, vpdIdParameter: CfnParameter, availabilityZonesParameter: CfnParameter, privateSubnetIdsParameter: CfnParameter): IVpc {
    const availabilityZones = availabilityZonesParameter.valueAsList
    const privateSubnetIds = privateSubnetIdsParameter.valueAsList
    return Vpc.fromVpcAttributes(stack, 'ImportedVPC', {
        vpcId: vpdIdParameter.valueAsString,
        availabilityZones: [Fn.select(0, availabilityZones)],
        privateSubnetIds: [Fn.select(0, privateSubnetIds)]
    });
}

export class SolutionsInfrastructureStack extends Stack {

    constructor(scope: Construct, id: string, props: SolutionsInfrastructureStackProps) {
        const finalId = props.stackNameSuffix ? `${id}-${props.stackNameSuffix}` : id
        super(scope, finalId, props);

        // Distinct description for ECS create vs import
        this.templateOptions.description = buildTemplateDescription(
            props.createVPC ? 'ECS_VPC_CREATE' : 'ECS_VPC_IMPORT'
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
            description: 'Specify the stage identifier which will be used in naming resources, e.g. dev,gamma,wave1.  ' +
                'This name should be unique for the all deployments in the region.',
            default: 'dev',
        });
        additionalParameters.push(stageParameter.logicalId)

        const stackMarker = `${stageParameter.valueAsString}-${Aws.REGION}`;
        const appRegistryAppARN = applyAppRegistry(this, stackMarker, props)

        new CfnDocument(this, "BootstrapShellDoc", {
            name: `BootstrapShellDoc-${stackMarker}`,
            documentType: "Session",
            content: {
                "schemaVersion": "1.0",
                "description": "Document to hold regional settings for Session Manager",
                "sessionType": "Standard_Stream",
                "inputs": {
                    "cloudWatchLogGroupName": "",
                    "cloudWatchEncryptionEnabled": true,
                    "cloudWatchStreamingEnabled": false,
                    "kmsKeyId": "",
                    "runAsEnabled": false,
                    "runAsDefaultUser": "",
                    "idleSessionTimeout": "60",
                    "maxSessionDuration": "",
                    "shellProfile": {
                        "linux": "cd /opensearch-migrations && sudo -s"
                    }
                }
            }
        })

        const solutionsUserAgent = `AwsSolution/${props.solutionId}/${props.solutionVersion}`

        const bootstrapRole = new Role(this, 'BootstrapRole', {
            assumedBy: new ServicePrincipal('ec2.amazonaws.com'),
            description: 'EC2 Bootstrap Role'
        });
        bootstrapRole.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName('AdministratorAccess'))

        new InstanceProfile(this, 'BootstrapInstanceProfile', {
            instanceProfileName: `bootstrap-instance-profile-${stackMarker}`,
            role: bootstrapRole
        })

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

            const availabilityZonesParameter = new CfnParameter(this, 'VPCAvailabilityZones', {
                type: 'List<AWS::EC2::AvailabilityZone::Name>',
                description: 'Select Availability Zones in the selected VPC. Please provide two zones at least, corresponding with the private subnets selected next.'
            });
            addParameterLabel(parameterLabels, availabilityZonesParameter, "Availability Zones")

            const privateSubnetIdsParameter = new CfnParameter(this, 'VPCPrivateSubnetIds', {
                type: 'List<AWS::EC2::Subnet::Id>',
                description: 'Select Private Subnets in the selected VPC. Please provide two subnets at least, corresponding with the availability zones selected previously.'
            });
            addParameterLabel(parameterLabels, privateSubnetIdsParameter, "Private Subnets")
            importedVPCParameters.push(vpcIdParameter.logicalId, availabilityZonesParameter.logicalId, privateSubnetIdsParameter.logicalId)
            vpc = importVPC(this, vpcIdParameter, availabilityZonesParameter, privateSubnetIdsParameter);
        }

        const exportString = generateExportString({
            "MIGRATIONS_APP_REGISTRY_ARN": appRegistryAppARN,
            "MIGRATIONS_USER_AGENT": solutionsUserAgent,
            "VPC_ID": vpc.vpcId,
            "STAGE": stageParameter.valueAsString,
        })
        const cfnInitConfig: InitElement[] = [
            InitCommand.shellCommand(`echo "${exportString}" > /etc/profile.d/solutionsEnv.sh`),
            InitFile.fromFileInline("/opensearch-migrations/initBootstrap.sh", './initBootstrap.sh', {
                mode: "000744"
            }),
        ]

        // Generated by running `source ./create-ami-map.sh`
        const amiMap: Record<string, string> = {
            'af-south-1': 'ami-0f2291b432361993c',
            'ap-east-1': 'ami-00f710addf15e93fd',
            'ap-east-2': 'ami-0c83eb5a8cf16e49e',
            'ap-northeast-1': 'ami-070d2b24928913a49',
            'ap-northeast-2': 'ami-0dec6548c7c0d0a96',
            'ap-northeast-3': 'ami-03fb5631b18a1e8b1',
            'ap-south-1': 'ami-0317b0f0a0144b137',
            'ap-south-2': 'ami-01cfb0266fc955899',
            'ap-southeast-1': 'ami-0249e9b9816d90e03',
            'ap-southeast-2': 'ami-0dc5681784bd0eed6',
            'ap-southeast-3': 'ami-0d5f3e23c61fbb3e8',
            'ap-southeast-4': 'ami-0d08a8bc3f8c55060',
            'ap-southeast-5': 'ami-037681534ae98d18d',
            'ap-southeast-6': 'ami-095e80b9bf0edce33',
            'ap-southeast-7': 'ami-00c039356f857110a',
            'ca-central-1': 'ami-09547c8673abb0190',
            'ca-west-1': 'ami-0c9b709ea3a1b239f',
            'eu-central-1': 'ami-0bae57ee7c4478e01',
            'eu-central-2': 'ami-061a5b9d1baf6a08c',
            'eu-north-1': 'ami-0c83cb1c664994bbd',
            'eu-south-1': 'ami-048c49d802920749f',
            'eu-south-2': 'ami-026fbd7c6075fc91a',
            'eu-west-1': 'ami-080ecf65f4d838a6e',
            'eu-west-2': 'ami-075f150fc1ca69e71',
            'eu-west-3': 'ami-030ebd4d4694126d2',
            'il-central-1': 'ami-0512ff1cc00a954e8',
            'me-central-1': 'ami-024c959cf530c88c1',
            'me-south-1': 'ami-0d0e834c3881c3024',
            'mx-central-1': 'ami-04c5c66626358bc0b',
            'sa-east-1': 'ami-08dd439af9c3f1639',
            'us-east-1': 'ami-0c1fe732b5494dc14',
            'us-east-2': 'ami-05efc83cb5512477c',
            'us-west-1': 'ami-0e2694e3cb74a4c35',
            'us-west-2': 'ami-0320940581663281e',
        };

        // Requires a gov cloud account to execute these commands
        /*
            aws ssm get-parameter \
                --region us-gov-west-1 \
                --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64 \
                --query 'Parameter.Value' \
                --output text
        */
        amiMap['us-gov-west-1'] = 'ami-08f42c51760f3e3af';
        /*
            aws ssm get-parameter \
                --region us-gov-east-1 \
                --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64 \
                --query 'Parameter.Value' \
                --output text
        */
        amiMap['us-gov-east-1'] = 'ami-0c16bde0528963329';

        const securityGroup = new SecurityGroup(this, 'BootstrapSecurityGroup', {
            vpc: vpc,
            allowAllOutbound: true,
            allowAllIpv6Outbound: true,
        });
        new Instance(this, 'BootstrapEC2Instance', {
            vpc: vpc,
            vpcSubnets: {
                subnets: vpc.privateSubnets,
            },
            instanceName: `bootstrap-instance-${stackMarker}`,
            instanceType: InstanceType.of(InstanceClass.T3, InstanceSize.LARGE),
            machineImage: new GenericLinuxImage(amiMap),
            role: bootstrapRole,
            blockDevices: [
                {
                    deviceName: "/dev/xvda",
                    volume: BlockDeviceVolume.ebs(50)
                }
            ],
            init: CloudFormationInit.fromElements(...cfnInitConfig),
            initOptions: {
                printLog: true,
            },
            requireImdsv2: true,
            securityGroup
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
