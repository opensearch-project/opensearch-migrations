#!/usr/bin/env node
import { App } from 'aws-cdk-lib';
import { CiInfraStack } from '../lib/ci-infra-stack';

const app = new App();

function requiredContext(key: string): string {
    const value = app.node.tryGetContext(key);
    if (typeof value !== 'string' || value.length === 0) {
        throw new Error(`Missing CDK context value '${key}'`);
    }
    return value;
}

new CiInfraStack(app, 'osm-ci-infra', {
    env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: 'us-east-1' },
    github: {
        repository: requiredContext('githubRepository'),
        subjectPrefix: requiredContext('githubSubjectPrefix'),
        workflowRef: app.node.tryGetContext('githubWorkflowRef') ?? 'refs/heads/main',
    },
    testRoleManagedPolicyArns: app.node.tryGetContext('testRoleManagedPolicyArns') ?? [],
});
