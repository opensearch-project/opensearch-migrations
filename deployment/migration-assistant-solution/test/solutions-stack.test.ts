import { describe, test } from '@jest/globals';
import { Template } from 'aws-cdk-lib/assertions';
import { App } from 'aws-cdk-lib';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';

describe('Solutions stack', () => {
    test('Generate bootstrap stack with create VPC', () => {
        const app = new App();
        const stack = new SolutionsInfrastructureStack(app, 'TestBootstrapStack', {
            solutionId: 'SO0000',
            solutionName: 'test-solution',
            solutionVersion: '0.0.1',
            codeBucket: 'test-bucket',
            createVPC: true
        });
        const template = Template.fromStack(stack);
        template.resourceCountIs('AWS::EC2::VPC', 1)
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::Application', 1)
        template.hasResourceProperties('AWS::EC2::Instance', {
            InstanceType: "t3.large"
        });
    });
    test('Generate bootstrap stack with imported VPC', () => {
        const app = new App();
        const stack = new SolutionsInfrastructureStack(app, 'TestBootstrapStack', {
            solutionId: 'SO0000',
            solutionName: 'test-solution',
            solutionVersion: '0.0.1',
            codeBucket: 'test-bucket',
            createVPC: false
        });
        const template = Template.fromStack(stack);
        template.resourceCountIs('AWS::EC2::VPC', 0)
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::Application', 1)
        template.hasResourceProperties('AWS::EC2::Instance', {
            InstanceType: "t3.large"
        });
    });
});
