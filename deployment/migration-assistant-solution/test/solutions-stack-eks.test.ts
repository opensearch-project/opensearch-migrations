import { describe, test } from '@jest/globals';
import {Template} from 'aws-cdk-lib/assertions';
import { App } from 'aws-cdk-lib';
import {SolutionsInfrastructureEKSStack} from "../lib/solutions-stack-eks";

describe('Solutions stack', () => {
    const defaultProperties = {
        solutionId: 'SO0000',
        solutionName: 'test-solution',
        solutionVersion: '0.0.1',
        codeBucket: 'test-bucket',
        createVPC: true,
        env: {
            region: 'us-west-1'
        }
    };

    test('Generate migration assistant stack with create VPC', () => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack', defaultProperties);
        const template = Template.fromStack(stack);
        verifyResources(template, {
            vpcCount: 1,
            vpcEndpointCount: 5,
            subnetCount: 4,
            natGatewayCount: 2
        });
        verifyParameters(template, {
            vpcIdEnabled: false,
            vpcSubnetsEnabled: false
        })
    });

    test('Generate migration assistant stack with imported VPC', () => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack', {
            ...defaultProperties,
            createVPC: false
        });
        const template = Template.fromStack(stack);
        verifyResources(template, {
            vpcCount: 0,
            vpcEndpointCount: 8,
            subnetCount: 0,
            natGatewayCount: 0
        });
        verifyParameters(template, {
            vpcIdEnabled: true,
            vpcSubnetsEnabled: true
        })
    });

    test('Generate migration assistant stack with create VPC in Gov Region', () => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack',  {
            ...defaultProperties,
            env: {
                region : "us-gov-east-1",
            },
        });
        const template = Template.fromStack(stack);
        verifyResources(template, {
            vpcCount: 1,
            vpcEndpointCount: 5,
            subnetCount: 4,
            natGatewayCount: 2
        });
        verifyParameters(template, {
            vpcIdEnabled: false,
            vpcSubnetsEnabled: false
        })
    });

    test('Migration stack with new VPC matches snapshot', () => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack', defaultProperties);
        const template = Template.fromStack(stack).toJSON();
        expect(template).toMatchSnapshot();
    });

    test('Migration stack with import VPC matches snapshot', () => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack', {
            ...defaultProperties,
            createVPC: false
        });
        const template = Template.fromStack(stack).toJSON();
        expect(template).toMatchSnapshot();
    });

    /**
     * How deployer-supplied tags (aws-bootstrap.sh --tags) are expected to reach each resource
     * type this stack creates.
     *
     * This map is maintained BY HAND on purpose. Neither available oracle is trustworthy:
     * `TagManager.isTaggable()` reflects CDK's L1 codegen rather than CloudFormation (it reports
     * AWS::EC2::VPCEndpoint, AWS::SSM::Parameter, AWS::EKS::AccessEntry and
     * AWS::EKS::PodIdentityAssociation as untaggable, which is wrong for CloudFormation), and the
     * bundled @aws-cdk/cfnspec is stale enough that it does not know the EKS types exist at all.
     *
     * So this test does NOT prove that tags land. It proves only that every resource type in the
     * template has been consciously classified: add a resource type and this fails until someone
     * decides which bucket it belongs in. Whether tags actually land is verified against a live
     * deployment -- see deployment/k8s/aws/README.md, "Tagging everything the deployment creates".
     */
    const enum TagCoverage {
        /** CloudFormation applies stack tags to this type, so `--tags` covers it. */
        STACK_TAGS = 'stack tags',
        /** The type has no tag support in CloudFormation; nothing to assert. */
        NOT_TAGGABLE = 'not taggable',
    }

    const TAG_COVERAGE: Record<string, TagCoverage> = {
        'AWS::EC2::EIP': TagCoverage.STACK_TAGS,
        'AWS::EC2::InternetGateway': TagCoverage.STACK_TAGS,
        'AWS::EC2::NatGateway': TagCoverage.STACK_TAGS,
        'AWS::EC2::RouteTable': TagCoverage.STACK_TAGS,
        'AWS::EC2::SecurityGroup': TagCoverage.STACK_TAGS,
        'AWS::EC2::Subnet': TagCoverage.STACK_TAGS,
        'AWS::EC2::VPC': TagCoverage.STACK_TAGS,
        'AWS::EC2::VPCEndpoint': TagCoverage.STACK_TAGS,
        'AWS::ECR::Repository': TagCoverage.STACK_TAGS,
        'AWS::EKS::AccessEntry': TagCoverage.STACK_TAGS,
        'AWS::EKS::Cluster': TagCoverage.STACK_TAGS,
        'AWS::EKS::PodIdentityAssociation': TagCoverage.STACK_TAGS,
        'AWS::IAM::Role': TagCoverage.STACK_TAGS,
        'AWS::SSM::Parameter': TagCoverage.STACK_TAGS,

        // Association/attachment records and inline policies carry no tags of their own.
        'AWS::EC2::EgressOnlyInternetGateway': TagCoverage.NOT_TAGGABLE,
        'AWS::EC2::Route': TagCoverage.NOT_TAGGABLE,
        'AWS::EC2::SubnetRouteTableAssociation': TagCoverage.NOT_TAGGABLE,
        'AWS::EC2::VPCCidrBlock': TagCoverage.NOT_TAGGABLE,
        'AWS::EC2::VPCGatewayAttachment': TagCoverage.NOT_TAGGABLE,
        'AWS::IAM::Policy': TagCoverage.NOT_TAGGABLE,
    };

    test.each([
        ['create VPC', true],
        ['import VPC', false],
    ])('every resource type in the %s stack is classified for tag coverage', (_name, createVPC) => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack', {
            ...defaultProperties,
            createVPC,
        });
        const resources = Template.fromStack(stack).toJSON().Resources ?? {};
        const unclassified = [...new Set(
            Object.values(resources).map((r: any) => r.Type as string)
        )].filter(type => !(type in TAG_COVERAGE)).sort();

        expect(unclassified).toEqual([]);
    });

    test('Auto Mode tag propagation policy stays scoped to this cluster', () => {
        const stack = new SolutionsInfrastructureEKSStack(new App(), 'TestMigrationAssistantStack', defaultProperties);
        const resources = Template.fromStack(stack).toJSON().Resources ?? {};

        // Without this policy, a single deployer tag turns every RunInstances and CreateVolume that
        // EKS Auto Mode issues into AccessDenied, so the cluster silently stops scaling. Assert it
        // exists and that nobody has widened the condition that keeps it scoped to one cluster.
        const policies = Object.values(resources).filter(
            (r: any) => r.Type === 'AWS::IAM::Policy'
                && r.Properties?.PolicyDocument?.Statement?.some((s: any) => s.Sid === 'Compute')
        ) as any[];
        expect(policies).toHaveLength(1);

        const statements = policies[0].Properties.PolicyDocument.Statement;
        expect(statements.map((s: any) => s.Sid).sort())
            .toEqual(['Compute', 'LoadBalancer', 'Networking', 'Storage']);
        for (const statement of statements) {
            expect(statement.Effect).toBe('Allow');
            // Auto Mode assumes the cluster role with eks:eks-cluster-name as a session tag, so
            // matching the request tag against the principal tag cannot be spoofed by the caller.
            expect(statement.Condition?.StringEquals).toEqual({
                'aws:RequestTag/eks:eks-cluster-name': '${aws:PrincipalTag/eks:eks-cluster-name}',
            });
        }
    });

    function verifyResources(template: Template, props: { vpcCount: number, vpcEndpointCount: number,
        subnetCount: number, natGatewayCount: number }) {
        template.resourceCountIs('AWS::EC2::VPC', props.vpcCount);
        template.resourceCountIs('AWS::EC2::VPCEndpoint', props.vpcEndpointCount);
        template.resourceCountIs('AWS::EC2::Subnet', props.subnetCount);
        template.resourceCountIs('AWS::EC2::NatGateway', props.natGatewayCount);
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::Application', 0);
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::ResourceAssociation', 0);
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::AttributeGroup', 0);
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::AttributeGroupAssociation', 0);
        template.resourceCountIs('AWS::EKS::Cluster', 1);
        template.resourceCountIs('AWS::IAM::Role', 4);
    }

    function verifyParameters(template: Template, props: { vpcIdEnabled: boolean, vpcSubnetsEnabled: boolean}) {
        template.hasParameter('Stage', {
            Type: 'String',
            Default: "dev",
        });
        if (props.vpcIdEnabled) {
            template.hasParameter('VPCId', {
                Type: 'AWS::EC2::VPC::Id'
            });
        }
        if (props.vpcSubnetsEnabled) {
            template.hasParameter('VPCSubnetIds', {
                Type: 'List<AWS::EC2::Subnet::Id>'
            });
        }
    }
});
