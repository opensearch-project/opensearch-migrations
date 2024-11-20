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
    GenericLinuxImage,
    InitCommand,
    InitElement,
    InitFile,
    Instance,
    InstanceClass,
    InstanceSize,
    InstanceType,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {InstanceProfile, ManagedPolicy, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {CfnDocument} from "aws-cdk-lib/aws-ssm";
import {Application, AttributeGroup} from "@aws-cdk/aws-servicecatalogappregistry-alpha";

export interface SolutionsInfrastructureStackProps extends StackProps {
    readonly solutionId: string;
    readonly solutionName: string;
    readonly solutionVersion: string;
    readonly codeBucket: string;
    readonly createVPC: boolean;
}

interface ParameterLabel {
    default: string;
}

function applyAppRegistry(stack: Stack, stage: string, infraProps: SolutionsInfrastructureStackProps): string {
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

function importVPC(stack: Stack, vpdIdParameter: CfnParameter, availabilityZonesParameter: CfnParameter, privateSubnetIdsParameter: CfnParameter) {
    const availabilityZones = availabilityZonesParameter.valueAsList
    const privateSubnetIds = privateSubnetIdsParameter.valueAsList
    return Vpc.fromVpcAttributes(stack, 'ImportedVPC', {
        vpcId: vpdIdParameter.valueAsString,
        availabilityZones: [Fn.select(0, availabilityZones)],
        privateSubnetIds: [Fn.select(0, privateSubnetIds)]
    });
}

function generateExportString(exports:  Record<string, string>): string {
    return Object.entries(exports)
        .map(([key, value]) => `export ${key}=${value}`)
        .join("; ");
}

export class SolutionsInfrastructureStack extends Stack {

    constructor(scope: Construct, id: string, props: SolutionsInfrastructureStackProps) {
        super(scope, id, props);
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

        let vpc;
        if (props.createVPC) {
            vpc = new Vpc(this, 'Vpc', {});
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
        const amiMap: Record<string, string> =  {
            'us-east-2': 'ami-09da212cf18033880',
            'us-east-1': 'ami-0fff1b9a61dec8a5f',
            'us-west-1': 'ami-09b2477d43bc5d0ac',
            'us-west-2': 'ami-0d081196e3df05f4d',
            'ca-central-1': 'ami-0d9c7bbbda4b78ffd',
            'ap-south-1': 'ami-078264b8ba71bc45e',
            'sa-east-1': 'ami-0fd8b11b89c97edaf',
            'eu-north-1': 'ami-097c5c21a18dc59ea',
            'ap-northeast-1': 'ami-0ef29ab52ff72213b',
            'ap-northeast-2': 'ami-0e18fe6ecdad223e5',
            'ap-northeast-3': 'ami-022b677fdccc634eb',
            'eu-central-1': 'ami-0592c673f0b1e7665',
            'eu-west-2': 'ami-0b4c7755cdf0d9219',
            'eu-west-3': 'ami-0a3598a00eff32f66',
            'eu-west-1': 'ami-054a53dca63de757b',
            'ap-southeast-2': 'ami-0cf70e1d861e1dfb8',
            'ap-southeast-1': 'ami-0ad522a4a529e7aa8',
        };

        // Manually looked up with https://us-gov-east-1.console.amazonaws-us-gov.com/ec2/home?region=us-gov-east-1#AMICatalog:
        amiMap['us-gov-west-1'] = 'ami-0c428177c69dbc6ff';
        amiMap['us-gov-east-1'] = 'ami-0345e99d9ca0e18a1';

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
