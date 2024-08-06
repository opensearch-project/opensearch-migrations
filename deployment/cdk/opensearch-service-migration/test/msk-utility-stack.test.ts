import { createStackComposer } from "./test-utils";
import { Match, Template } from "aws-cdk-lib/assertions";
import { MSKUtilityStack } from "../lib/msk-utility-stack";
import { describe, beforeEach, afterEach, test, expect, jest } from '@jest/globals';
import { ContainerImage } from "aws-cdk-lib/aws-ecs";

jest.mock('aws-cdk-lib/aws-ecr-assets');
describe('MSKUtilityStack Tests', () => {
  beforeEach(() => {
    jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
    jest.resetAllMocks();
  });

  test('Test if mskEnablePublicEndpoints is provided, wait condition and max attempts are set for lambda custom resource', () => {
    const contextOptions = {
      vpcEnabled: true,
      migrationAssistanceEnabled: true,
      trafficReplayerServiceEnabled: true,
      mskEnablePublicEndpoints: true,
      mskRestrictPublicAccessTo: "10.0.0.0/32",
      mskRestrictPublicAccessType: "ipv4",
      sourceClusterEndpoint: "https://test-cluster",
    }

    const stacks = createStackComposer(contextOptions)

    const mskUtilityStack: MSKUtilityStack = (stacks.stacks.filter((s) => s instanceof MSKUtilityStack)[0]) as MSKUtilityStack
    const mskUtilityStackTemplate = Template.fromStack(mskUtilityStack)

    mskUtilityStackTemplate.hasResource("AWS::Lambda::Function", {
      Properties: {
        Environment: {
          Variables: {
            "MAX_ATTEMPTS": "4",
            "MSK_ARN": Match.anyValue()
          }
        }
      }
    })
    mskUtilityStackTemplate.resourceCountIs("AWS::CloudFormation::WaitConditionHandle", 1)
  });

  test('Test if mskEnablePublicEndpoints is not provided, single run lambda custom resource is created', () => {
    const contextOptions = {
      vpcEnabled: true,
      migrationAssistanceEnabled: true,
      trafficReplayerServiceEnabled: true,
      sourceClusterEndpoint: "https://test-cluster",
    }

    const stacks = createStackComposer(contextOptions)

    const mskUtilityStack: MSKUtilityStack = (stacks.stacks.filter((s) => s instanceof MSKUtilityStack)[0]) as MSKUtilityStack
    const mskUtilityStackTemplate = Template.fromStack(mskUtilityStack)

    mskUtilityStackTemplate.hasResource("AWS::Lambda::Function", {
      Properties: {
        Environment: {
          Variables: {
            "MSK_ARN": Match.anyValue()
          }
        }
      }
    })
    mskUtilityStackTemplate.resourceCountIs("AWS::CloudFormation::WaitConditionHandle", 0)
  });
});
