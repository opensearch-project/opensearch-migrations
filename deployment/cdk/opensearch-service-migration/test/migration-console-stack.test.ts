import { createStackComposer } from "./test-utils";
import { Capture, Match, Template } from "aws-cdk-lib/assertions";
import { MigrationConsoleStack } from "../lib/service-stacks/migration-console-stack";
import {ContainerImage} from "aws-cdk-lib/aws-ecs";
import {describe, beforeEach, afterEach, test, expect, jest} from '@jest/globals';
import {ReindexFromSnapshotStack} from "../lib";

jest.mock('aws-cdk-lib/aws-ecr-assets');
describe('Migration Console Stack Tests', () => {
    // Mock the implementation of fromDockerImageAsset before all tests
    beforeEach(() => {
        jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
    });

    // Optionally restore the original implementation after all tests
    afterEach(() => {
        jest.clearAllMocks();
        jest.resetModules();
        jest.restoreAllMocks();
        jest.resetAllMocks();
    });

    test('Migration Console task definition is updated when services.yaml inputs change', () => {
        const baseContextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            sourceClusterEndpoint: "https://test-cluster",
            reindexFromSnapshotServiceEnabled: true,
            trafficReplayerServiceEnabled: true,
        };

        // Create initial stack
        const initialStacks = createStackComposer(baseContextOptions);
        const initialMigrationConsoleStack = initialStacks.stacks.find(s => s instanceof MigrationConsoleStack) as MigrationConsoleStack;
        const rfsStack = initialStacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
        expect(rfsStack).toBeDefined();
        const initialTemplate = Template.fromStack(initialMigrationConsoleStack);

        // Capture initial task definition
        const initialTaskDefinitionCapture = new Capture();
        initialTemplate.hasResourceProperties("AWS::ECS::TaskDefinition", {
            Family: Match.stringLikeRegexp("migration-.*-migration-console"),
            ContainerDefinitions: initialTaskDefinitionCapture,
        });

        // Modify context options to change services.yaml
        const updatedContextOptions = {
            ...baseContextOptions,
            sourceClusterEndpoint: "https://updated-test-cluster",
        };

        // Create updated stack
        const updatedStacks = createStackComposer(updatedContextOptions);
        const updatedMigrationConsoleStack = updatedStacks.stacks.find(s => s instanceof MigrationConsoleStack) as MigrationConsoleStack;
        const updatedTemplate = Template.fromStack(updatedMigrationConsoleStack);

        // Capture updated task definition
        const updatedTaskDefinitionCapture = new Capture();
        updatedTemplate.hasResourceProperties("AWS::ECS::TaskDefinition", {
            Family: Match.stringLikeRegexp("migration-.*-migration-console"),
            ContainerDefinitions: updatedTaskDefinitionCapture,
        });

        // Compare task definitions
        const initialTaskDef = initialTaskDefinitionCapture.asArray();
        const updatedTaskDef = updatedTaskDefinitionCapture.asArray();

        // Check if the environment variables in the task definition have changed
        const initialEnv = initialTaskDef[0].Environment;
        const updatedEnv = updatedTaskDef[0].Environment;

        expect(initialEnv).not.toEqual(updatedEnv);

        // Specifically check if the MIGRATION_SERVICES_YAML_HASH has changed
        const initialYamlHash = initialEnv.find((env: any) => env.Name === "MIGRATION_SERVICES_YAML_HASH").Value;
        const updatedYamlHash = updatedEnv.find((env: any) => env.Name === "MIGRATION_SERVICES_YAML_HASH").Value;

        expect(initialYamlHash).not.toEqual(updatedYamlHash);
    });
});
