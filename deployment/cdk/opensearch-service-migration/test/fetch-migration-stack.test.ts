import {App} from 'aws-cdk-lib';
import {FetchMigrationStack} from "../lib/fetch-migration-stack";
import {Capture, Match, Template} from "aws-cdk-lib/assertions";
import {NetworkStack} from "../lib/network-stack";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {describe, test, expect} from '@jest/globals';
import { MigrationConsoleStack } from '../lib';
import { createStackComposer } from './test-utils';

describe('FetchMigrationStack Tests', () => {
  test('Test default fetch migration stack creates required resources', () => {
    const app = new App();

    const networkStack = new NetworkStack(app, "NetworkStack", {
      defaultDeployId: "default",
      sourceClusterEndpoint: "https://test-cluster",
      stage: "unit-test"
    })

    const fetchStack = new FetchMigrationStack(app, "FetchMigrationStack", {
      vpc: networkStack.vpc,
      dpPipelineTemplatePath: "./dp_pipeline_template.yaml",
      defaultDeployId: "default",
      stage: "unit-test",
      fargateCpuArch: CpuArchitecture.X86_64,
    })

    const template = Template.fromStack(fetchStack);
    template.resourceCountIs("AWS::ECS::TaskDefinition", 1)
    template.resourceCountIs("AWS::SecretsManager::Secret", 1)
    template.hasResourceProperties("AWS::SecretsManager::Secret", {
      Name: "unit-test-default-fetch-migration-pipelineConfig"
    })
  });

    test('IAM policy contains fetch migration IAM statements when fetch migration is enabled', () => {
        const contextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            fetchMigrationEnabled: true,
            sourceClusterEndpoint: "https://test-cluster",
        };

        const stacks = createStackComposer(contextOptions);
        const migrationConsoleStack = stacks.stacks.find(s => s instanceof MigrationConsoleStack) as MigrationConsoleStack;
        const migrationConsoleStackTemplate = Template.fromStack(migrationConsoleStack);

        const statementCapture = new Capture();
        migrationConsoleStackTemplate.hasResourceProperties("AWS::IAM::Policy", {
            PolicyDocument: Match.objectLike({
                Statement: statementCapture,
            })
        });

        const allStatements: any[] = statementCapture.asArray();
        const runTaskStatement = allStatements.find(statement => statement.Action.includes("ecs:RunTask"));
        const iamPassRoleStatement = allStatements.find(statement => statement.Action === "iam:PassRole");

        expect(runTaskStatement).toBeTruthy();
        expect(iamPassRoleStatement).toBeTruthy();
    });

    test('IAM policy does not contain fetch migration IAM statements when fetch migration is disabled', () => {
        const contextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            sourceClusterEndpoint: "https://test-cluster",
        };

        const stacks = createStackComposer(contextOptions);
        const migrationConsoleStack = stacks.stacks.find(s => s instanceof MigrationConsoleStack) as MigrationConsoleStack;
        const migrationConsoleStackTemplate = Template.fromStack(migrationConsoleStack);

        const statementCapture = new Capture();
        migrationConsoleStackTemplate.hasResourceProperties("AWS::IAM::Policy", {
            PolicyDocument: Match.objectLike({
                Statement: statementCapture,
            })
        });

        const allStatements: any[] = statementCapture.asArray();
        const runTaskStatement = allStatements.find(statement => statement.Action === "ecs:RunTask");
        const iamPassRoleStatement = allStatements.find(statement => statement.Action === "iam:PassRole");

        expect(runTaskStatement).toBeFalsy();
        expect(iamPassRoleStatement).toBeFalsy();
    });
});
