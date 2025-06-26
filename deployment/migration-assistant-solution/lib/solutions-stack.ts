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
    ParameterLabel
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

        // Generated with ../create-ami-map.sh
        const amiMap: Record<string, string> = {
            'us-east-2': 'ami-0fc82f4dabc05670b',
            'us-east-1': 'ami-05b10e08d247fb927',
            'il-central-1': 'ami-0632d5335bb97c65e',
            'us-west-1': 'ami-094b981da55429bfc',
            'af-south-1': 'ami-071ec74a9abd8fec2',
            'us-west-2': 'ami-027951e78de46a00e',
            'me-central-1': 'ami-0c4117cd3d8aa9f9a',
            'mx-central-1': 'ami-0692398a0c98b312e',
            'ca-central-1': 'ami-05073582a4b03d785',
            'ap-south-1': 'ami-0d682f26195e9ec0f',
            'ap-south-2': 'ami-09e23b3de35f110f6',
            'ap-east-1': 'ami-0123e5d7542358c86',
            'me-south-1': 'ami-0a95ef992b0368b4c',
            'sa-east-1': 'ami-02cfee28b56653f5c',
            'eu-north-1': 'ami-016038ae9cc8d9f51',
            'ca-west-1': 'ami-05586d5f95c77b005',
            'ap-northeast-1': 'ami-072298436ce5cb0c4',
            'ap-northeast-2': 'ami-075e056c0f3d02523',
            'ap-northeast-3': 'ami-0439cd8bc5628c9e8',
            'eu-south-1': 'ami-02c8b07ea6001f11a',
            'eu-south-2': 'ami-047456c943d393211',
            'eu-central-1': 'ami-06ee6255945a96aba',
            'eu-central-2': 'ami-0a0c3a3296ccc2a29',
            'eu-west-2': 'ami-00710ab5544b60cf7',
            'eu-west-3': 'ami-0446057e5961dfab6',
            'eu-west-1': 'ami-0a89fa9a6d8c7ad98',
            'ap-southeast-7': 'ami-043f00bcf35b3eab2',
            'ap-southeast-4': 'ami-0a9b2961cf0036d29',
            'ap-southeast-5': 'ami-0e5b1229fc8235ff7',
            'ap-southeast-2': 'ami-064b71eca68aadfb8',
            'ap-southeast-3': 'ami-02a732f5ab0d7b2a4',
            'ap-southeast-1': 'ami-0b03299ddb99998e9',
          };

        // Manually looked up with https://us-gov-east-1.console.amazonaws-us-gov.com/ec2/home?region=us-gov-east-1#AMICatalog:
        amiMap['us-gov-west-1'] = 'ami-06cf22f69c918a2c1';
        amiMap['us-gov-east-1'] = 'ami-066774057f581130f';

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
