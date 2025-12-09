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

    test('ECS stack with new VPC matches snapshot', () => {
        const stack = new SolutionsInfrastructureStack(new App(), 'TestMigrationAssistantStack', defaultProperties);
        const template = Template.fromStack(stack).toJSON();
        expect(template).toMatchSnapshot();
    });

    test('ECS stack with import VPC matches snapshot', () => {
        const stack = new SolutionsInfrastructureStack(new App(), 'TestMigrationAssistantStack', {
            ...defaultProperties,
            createVPC: false
        });
        const template = Template.fromStack(stack).toJSON();
        expect(template).toMatchSnapshot();
    });
});
