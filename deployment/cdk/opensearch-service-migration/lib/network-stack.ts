import {Stack} from "aws-cdk-lib";
import {
    IpAddresses, IVpc,
    Port,
    SecurityGroup,
    SubnetType,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {StackPropsExt} from "./stack-composer";
import {StringParameter} from "aws-cdk-lib/aws-ssm";

export interface networkStackProps extends StackPropsExt {
    readonly vpcId?: string
    readonly availabilityZoneCount?: number
}

export class NetworkStack extends Stack {
    public readonly vpc: IVpc;

    constructor(scope: Construct, id: string, props: networkStackProps) {
        super(scope, id, props);

        // Retrieve existing VPC
        if (props.vpcId) {
            this.vpc = Vpc.fromLookup(this, 'domainVPC', {
                vpcId: props.vpcId,
            });
        }
        // Create new VPC
        else {
            this.vpc = new Vpc(this, 'domainVPC', {
                // IP space should be customized for use cases that have specific IP range needs
                ipAddresses: IpAddresses.cidr('10.0.0.0/16'),
                maxAzs: props.availabilityZoneCount ? props.availabilityZoneCount : 1,
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
        // new StringParameter(this, 'SSMParameterVpcId', {
        //     description: 'OpenSearch migration parameter for VPC id',
        //     parameterName: `/migration/${props.stage}/vpcId`,
        //     stringValue: vpc.vpcId
        // });

        // Create a default SG which only allows members of this SG to access the Domain endpoints
        const defaultSecurityGroup = new SecurityGroup(this, 'domainMigrationAccessSG', {
            vpc: this.vpc,
            allowAllOutbound: false,
        });
        defaultSecurityGroup.addIngressRule(defaultSecurityGroup, Port.allTraffic());

        new StringParameter(this, 'SSMParameterOpenSearchAccessGroupId', {
            description: 'OpenSearch migration parameter for target OpenSearch access security group id',
            parameterName: `/migration/${props.stage}/osAccessSecurityGroupId`,
            stringValue: defaultSecurityGroup.securityGroupId
        });

    }
}