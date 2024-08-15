import { Template } from "aws-cdk-lib/assertions";
import { OpenSearchDomainStack } from "../lib/opensearch-domain-stack";
import { createStackComposer, createStackComposerOnlyPassedContext } from "./test-utils";
import { App } from "aws-cdk-lib";
import { StackComposer } from "../lib/stack-composer";
import { KafkaStack } from "../lib";
import { describe, beforeEach, afterEach, test, expect, jest } from '@jest/globals';
import { ContainerImage } from "aws-cdk-lib/aws-ecs";

jest.mock('aws-cdk-lib/aws-ecr-assets');
describe('Stack Composer Tests', () => {
  beforeEach(() => {
    jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
    jest.resetAllMocks();
  });

  test('Test empty string provided for a parameter which has a default value, uses the default value', () => {
    const contextOptions = {
      domainName: ""
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  test('Test invalid engine version format throws error', () => {
    const contextOptions = {
      // Should be OS_1.3
      engineVersion: "OpenSearch_1.3"
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test ES 7.10 engine version format is parsed', () => {
    const contextOptions = {
      engineVersion: "ES_7.10"
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  test('Test OS 1.3 engine version format is parsed', () => {
    const contextOptions = {
      engineVersion: "OS_1.3"
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  test('Test access policy is parsed for proper array format', () => {
    const contextOptions = {
      accessPolicies:
        {
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Principal": {"AWS": "arn:aws:iam::12345678912:user/test-user"},
              "Action": "es:ESHttp*",
              "Resource": "arn:aws:es:us-east-1:12345678912:domain/test-os-domain/*"
            },
            {
              "Effect": "Allow",
              "Principal": {"AWS": "arn:aws:iam::12345678912:user/test-user2"},
              "Action": "es:ESHttp*",
              "Resource": "arn:aws:es:us-east-1:12345678912:domain/test-os-domain/*"
            }]
        }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that accessPolicies policy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
  })

  test('Test access policy is parsed for proper block format', () => {
    const contextOptions = {
      accessPolicies:
        {
          "Version": "2012-10-17",
          "Statement": {
            "Effect": "Allow",
            "Principal": {"AWS": "*"},
            "Action": "es:ESHttp*",
            "Resource": "arn:aws:es:us-east-1:12345678912:domain/test-os-domain/*"
          }
        }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that accessPolicies policy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
  })

  test('Test access policy missing Statement throws error', () => {
    const contextOptions = {
      accessPolicies: {"Version": "2012-10-17"}
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test access policy with empty Statement array throws error', () => {
    const contextOptions = {
      accessPolicies: {"Version": "2012-10-17", "Statement": []}
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test access policy with empty Statement block throws error', () => {
    const contextOptions = {
      accessPolicies: {"Version": "2012-10-17", "Statement": {}}
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test access policy with improper Statement throws error', () => {
    const contextOptions = {
      // Missing required fields in Statement
      accessPolicies: {"Version": "2012-10-17", "Statement": [{"Effect": "Allow"}]}
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test invalid TLS security policy throws error', () => {
    const contextOptions = {
      tlsSecurityPolicy: "TLS_0_9"
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test invalid EBS volume type throws error', () => {
    const contextOptions = {
      ebsVolumeType: "GP0",
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test invalid domain removal policy type throws error', () => {
    const contextOptions = {
      domainRemovalPolicy: "DELETE",
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test that app registry association is created when migrationsAppRegistryARN is provided', () => {
    const contextOptions = {
      stage: "unit-test"
    }

    const app = new App({
      context: {
        contextId: "unit-test-config",
        "unit-test-config": contextOptions
      }
    })
    const stacks = new StackComposer(app, {
      migrationsAppRegistryARN: "arn:aws:servicecatalog:us-west-2:12345678912:/applications/12345abcdef",
      env: {account: "test-account", region: "us-east-1"},
      migrationsSolutionVersion: "1.0.1"
    })

    const domainStack = stacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::ServiceCatalogAppRegistry::ResourceAssociation", 1)
  })

  test('Test that with analytics and assistance stacks enabled, creates one opensearch domains', () => {
    const contextOptions = {
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true,
      sourceClusterEndpoint: "https://test-cluster",
    }

    const openSearchStacks = createStackComposer(contextOptions)
    const domainStacks = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)
    expect(domainStacks.length).toEqual(1)
  })

  test('Test that loading context via a file is successful', () => {
    const contextOptions = {
      contextFile: './test/resources/sample-context-file.json',
      contextId: 'unit-test-1'
    }
    const stacks = createStackComposerOnlyPassedContext(contextOptions)
    const kafkaContainerStack = stacks.stacks.filter((s) => s instanceof KafkaStack)
    expect(kafkaContainerStack.length).toEqual(1)
  })

  test('Test that loading context via a file errors if file does not exist', () => {
    const contextOptions = {
      contextFile: './test/resources/missing-file.json',
      contextId: 'unit-test-1'
    }

    const createStackFunc = () => createStackComposerOnlyPassedContext(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test that loading context via a file errors if file is not proper json', () => {
    const contextOptions = {
      contextFile: './test/resources/invalid-context-file.json',
      contextId: 'unit-test-1'
    }

    const createStackFunc = () => createStackComposerOnlyPassedContext(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test that loading context via a file errors if contextId does not exist', () => {
    const contextOptions = {
      contextFile: './test/resources/sample-context-file.json',
      contextId: 'unit-test-fake'
    }

    const createStackFunc = () => createStackComposerOnlyPassedContext(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test that a context with no source cluster details succeeds if sourceClusterDisabled', () => {
    const sourceClusterDisabledContextOptions = {
      sourceClusterDisabled: true,
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true,

    }
    const openSearchStacks = createStackComposer(sourceClusterDisabledContextOptions)
    expect(openSearchStacks.stacks).toHaveLength(4)

    const sourceClusterNotExplicitlyDisabledContextOptions = {
      sourceClusterDisabled: false,
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true
    }
    const sourceClusterNotExplicitlyDisabledCreateStackFunc = () => createStackComposerOnlyPassedContext(sourceClusterNotExplicitlyDisabledContextOptions)
    expect(sourceClusterNotExplicitlyDisabledCreateStackFunc).toThrow()

    const sourceClusterDisabledWithEndpointContextOptions = {
      sourceClusterDisabled: true,
      sourceClusterEndpoint: "XXXXXXXXXXXXXXXXXXXX",
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true
    }
    let sourceClusterDisabledWithEndpointCreateStackFunc = () => createStackComposer(sourceClusterDisabledWithEndpointContextOptions)
    expect (sourceClusterDisabledWithEndpointCreateStackFunc).toThrow()
  })
})
