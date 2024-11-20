import { describe, test } from '@jest/globals';
import { Template } from 'aws-cdk-lib/assertions';
import { App } from 'aws-cdk-lib';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';

describe('Solutions stack', () => {
    test('Generate migration assistant stack with create VPC', () => {
        const app = new App();
        const stack = new SolutionsInfrastructureStack(app, 'TestMigrationAssistantStack', {
            solutionId: 'SO0000',
            solutionName: 'test-solution',
            solutionVersion: '0.0.1',
            codeBucket: 'test-bucket',
            createVPC: true,
            env: {
                region: 'us-west-1'
            }
        });
        const template = Template.fromStack(stack);
        template.resourceCountIs('AWS::EC2::VPC', 1)
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::Application', 1)
        template.hasResourceProperties('AWS::EC2::Instance', {
            InstanceType: "t3.large"
        });
    });
    test('Generate migration assistant stack with imported VPC', () => {
        const app = new App();
        const stack = new SolutionsInfrastructureStack(app, 'TestMigrationAssistantStack', {
            solutionId: 'SO0000',
            solutionName: 'test-solution',
            solutionVersion: '0.0.1',
            codeBucket: 'test-bucket',
            createVPC: false,
            env: {
                region: 'us-west-1'
            }
        });
        const template = Template.fromStack(stack);
        template.resourceCountIs('AWS::EC2::VPC', 0)
        template.resourceCountIs('AWS::ServiceCatalogAppRegistry::Application', 1)
        template.hasResourceProperties('AWS::EC2::Instance', {
            InstanceType: "t3.large"
        });
    });
});
