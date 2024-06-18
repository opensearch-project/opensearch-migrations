import {createStackComposer} from "./test-utils";
import {Capture, Match, Template} from "aws-cdk-lib/assertions";
import {MigrationConsoleStack} from "../lib/service-stacks/migration-console-stack";
import * as yaml from 'yaml';


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
    const runTaskStatement = allStatements.find(statement => statement.Action.includes("ecs:RunTask"))
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

test('Test that services yaml parameter is created', () => {
    const contextOptions = {
        vpcEnabled: true,
        migrationAssistanceEnabled: true,
        migrationConsoleServiceEnabled: true
    }

    const stacks = createStackComposer(contextOptions)

    const migrationConsoleStack: MigrationConsoleStack = (stacks.stacks.filter((s) => s instanceof MigrationConsoleStack)[0]) as MigrationConsoleStack
    const migrationConsoleStackTemplate = Template.fromStack(migrationConsoleStack)

    const valueCapture = new Capture();
    migrationConsoleStackTemplate.hasResourceProperties("AWS::SSM::Parameter", {
        Type: "String",
        Name: Match.stringLikeRegexp("/migration/.*/.*/servicesYamlFile"),
        Value: valueCapture,
    });
    const value = valueCapture.asObject()
    expect(value).toBeDefined();
    expect(value['Fn::Join']).toBeInstanceOf(Array);
    expect(value['Fn::Join'][1]).toBeInstanceOf(Array)
    // join the strings together to get the yaml file contents
    const yamlFileContents = value['Fn::Join'][1].join('')
    expect(yamlFileContents).toContain('source_cluster')
    expect(yamlFileContents).toContain('target_cluster')
    expect(yamlFileContents).toContain('metrics_source:\n  cloudwatch:')

    // Validates that the file can be parsed as valid yaml and has the expected fields
    const parsedFromYaml = yaml.parse(yamlFileContents);
    expect(Object.keys(parsedFromYaml)).toEqual(['source_cluster', 'target_cluster', 'metrics_source'])

})