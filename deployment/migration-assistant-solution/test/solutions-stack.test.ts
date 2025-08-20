import { describe, test } from '@jest/globals';
import {Template} from 'aws-cdk-lib/assertions';
import { App } from 'aws-cdk-lib';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';

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
        const stack = new SolutionsInfrastructureStack(new App(), 'TestMigrationAssistantStack', defaultProperties);
        const template = Template.fromStack(stack);
        verifyResources(template, {
            vpcCount: 1,
            vpcEndpointCount: 5,
            subnetCount: 4,
            natGatewayCount: 2
        });
    });

    test('Generate migration assistant stack with create VPC in Gov Region', () => {
        const stack = new SolutionsInfrastructureStack(new App(), 'TestMigrationAssistantStack',  {
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
    });

    test('Generate migration assistant stack with imported VPC', () => {
        const stack = new SolutionsInfrastructureStack(new App(), 'TestMigrationAssistantStack', {
            ...defaultProperties,
            createVPC: false
        });
        const template = Template.fromStack(stack);
        verifyResources(template, {
            vpcCount: 0,
            vpcEndpointCount: 0,
            subnetCount: 0,
            natGatewayCount: 0
        });
    });

    function verifyResources(template: Template, props: { vpcCount: number, vpcEndpointCount: number,
        subnetCount: number, natGatewayCount: number }) {

        template.resourceCountIs('AWS::EC2::VPC', props.vpcCount);
        template.resourceCountIs('AWS::EC2::VPCEndpoint', props.vpcEndpointCount);
        template.resourceCountIs('AWS::EC2::Subnet', props.subnetCount);
        template.resourceCountIs('AWS::EC2::NatGateway', props.natGatewayCount);
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::Application', 1);
        template.hasResourceProperties('AWS::EC2::Instance', {
            InstanceType: "t3.large"
        });
        template.hasResourceProperties('AWS::EC2::LaunchTemplate', {
            LaunchTemplateData: {
                MetadataOptions: {
                    HttpTokens: "required"
                }
            }
        });
    }
});
