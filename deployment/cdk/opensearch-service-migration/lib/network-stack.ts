import {CfnOutput, Stack, StackProps} from "aws-cdk-lib";
import {
    IpAddresses,
    ISecurityGroup,
    IVpc,
    Peer,
    Port,
    SecurityGroup,
    SubnetFilter,
    SubnetSelection,
    SubnetType,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {StackPropsExt} from "./stack-composer";

export interface networkStackProps extends StackPropsExt {
    readonly vpcId?: string
    readonly vpcSubnetIds?: string[]
    readonly vpcSecurityGroupIds?: string[]
    readonly availabilityZoneCount?: number
}


export class NetworkStack extends Stack {

    public readonly vpc: IVpc;
    public readonly domainSubnets: SubnetSelection[]|undefined;
    public readonly defaultDomainAccessSecurityGroup: ISecurityGroup;
    public readonly domainSecurityGroups: ISecurityGroup[];

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

        // If specified, these subnets will be selected to place the Domain nodes in. Otherwise, this is not provided
        // to the Domain as it has existing behavior to select private subnets from a given VPC
        if (props.vpcSubnetIds) {
            const selectSubnets = this.vpc.selectSubnets({
                subnetFilters: [SubnetFilter.byIds(props.vpcSubnetIds)]
            })
            this.domainSubnets = [selectSubnets]
        }

        // Retrieve existing SGs to apply to VPC Domain endpoints
        const securityGroups: ISecurityGroup[] = []
        if (props.vpcSecurityGroupIds) {
            for (let i = 0; i < props.vpcSecurityGroupIds.length; i++) {
                securityGroups.push(SecurityGroup.fromLookupById(this, "domainSecurityGroup-" + i, props.vpcSecurityGroupIds[i]))
            }
        }
        // Create a default SG which only allows members of this SG to access the Domain endpoints
        const defaultSecurityGroup = new SecurityGroup(this, 'domainMigrationAccessSG', {
            vpc: this.vpc,
            allowAllOutbound: false,
        });
        defaultSecurityGroup.addIngressRule(defaultSecurityGroup, Port.allTraffic());
        securityGroups.push(defaultSecurityGroup)
        this.defaultDomainAccessSecurityGroup = defaultSecurityGroup
        this.domainSecurityGroups = securityGroups

        new CfnOutput(this, 'CopilotDomainSGExports', {
            value: `export MIGRATION_DOMAIN_SG_ID=${defaultSecurityGroup.securityGroupId}`,
            description: 'Domain Security Group created by CDK that is needed for Copilot container deployments',
        });

    }
}