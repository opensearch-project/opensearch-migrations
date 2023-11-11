import {createStackComposer} from "./test-utils";
import {Capture, Match, Template} from "aws-cdk-lib/assertions";
import {MigrationConsoleStack} from "../lib/service-stacks/migration-console-stack";


test('Test that IAM policy contains fetch migration IAM statements when fetch migration is enabled', () => {
    const contextOptions = {
        vpcEnabled: true,
        migrationAssistanceEnabled: true,
        migrationConsoleServiceEnabled: true,
        fetchMigrationEnabled: true
    }

    const stacks = createStackComposer(contextOptions)

    const migrationConsoleStack: MigrationConsoleStack = (stacks.stacks.filter((s) => s instanceof MigrationConsoleStack)[0]) as MigrationConsoleStack
    const migrationConsoleStackTemplate = Template.fromStack(migrationConsoleStack)

    const statementCapture = new Capture();
    migrationConsoleStackTemplate.hasResourceProperties("AWS::IAM::Policy", {
        PolicyDocument: Match.objectLike({
            Statement: statementCapture,
        })
    })
    const allStatements: any[] = statementCapture.asArray()
    const runTaskStatement = allStatements.find(statement => statement.Action == "ecs:RunTask")
    const iamPassRoleStatement = allStatements.find(statement => statement.Action == "iam:PassRole")
    expect(runTaskStatement).toBeTruthy()
    expect(iamPassRoleStatement).toBeTruthy()
})

test('Test that IAM policy does not contain fetch migration IAM statements when fetch migration is disabled', () => {
    const contextOptions = {
        vpcEnabled: true,
        migrationAssistanceEnabled: true,
        migrationConsoleServiceEnabled: true
    }

    const stacks = createStackComposer(contextOptions)

    const migrationConsoleStack: MigrationConsoleStack = (stacks.stacks.filter((s) => s instanceof MigrationConsoleStack)[0]) as MigrationConsoleStack
    const migrationConsoleStackTemplate = Template.fromStack(migrationConsoleStack)

    const statementCapture = new Capture();
    migrationConsoleStackTemplate.hasResourceProperties("AWS::IAM::Policy", {
        PolicyDocument: Match.objectLike({
            Statement: statementCapture,
        })
    })
    const allStatements: any[] = statementCapture.asArray()
    const runTaskStatement = allStatements.find(statement => statement.Action == "ecs:RunTask")
    const iamPassRoleStatement = allStatements.find(statement => statement.Action == "iam:PassRole")
    expect(runTaskStatement).toBeFalsy()
    expect(iamPassRoleStatement).toBeFalsy()
})