import { Template } from "aws-cdk-lib/assertions";
import { OpenSearchDomainStack } from "../lib/opensearch-domain-stack";
import { createStackComposer, createStackComposerOnlyPassedContext } from "./test-utils";
import { App } from "aws-cdk-lib";
import { StackComposer } from "../lib/stack-composer";
import { KafkaStack, MigrationConsoleStack } from "../lib";
import { describe, beforeEach, afterEach, test, expect, jest } from '@jest/globals';
import { ContainerImage } from "aws-cdk-lib/aws-ecs";

describe('Stack Composer Tests', () => {
  beforeEach(() => {
    jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
  });

  test('Test empty string provided for a parameter which has a default value, uses the default value', () => {
    const contextOptions = {
      domainName: "",
      sourceCluster: {
        disabled: true
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  function testManagedServiceSourceSnapshot(
    { sourceAuth, additionalOptions }: { sourceAuth: Record<string, unknown>; additionalOptions: Record<string, unknown> },
    expectedRoleCount: number,
    description: string
  ) {
    test(description, () => {
      const contextOptions = {
        sourceCluster: {
          "endpoint": "https://test-cluster",
          "auth": sourceAuth,
          "version": "ES_7.10"
        },
        targetCluster: {
          "endpoint": "https://test-cluster",
          "auth": {"type": "none"},
          "version": "OS_1.3"
        },
        vpcEnabled: true,
        migrationConsoleServiceEnabled: true,
        migrationAssistanceEnabled: true,
        reindexFromSnapshotServiceEnabled: true,
        ...additionalOptions
      };

      const openSearchStacks = createStackComposer(contextOptions);
      const migrationConsoleStack = openSearchStacks.stacks.filter((s) => s instanceof MigrationConsoleStack)[0];
      const migrationConsoleTemplate = Template.fromStack(migrationConsoleStack);
      migrationConsoleTemplate.resourceCountIs("AWS::IAM::Role", expectedRoleCount);
      if (expectedRoleCount === 3) {
        migrationConsoleTemplate.hasResourceProperties("AWS::IAM::Role", {
          RoleName: "OSMigrations-unit-test-us-east-1-default-SnapshotRole"
        });
      }
    });
  }

  const sigv4Auth = {
    "type": "sigv4",
    "region": "us-east-1",
    "serviceSigningName": "es"
  };

  const noAuth = {"type": "none"};

  testManagedServiceSourceSnapshot(
    {
      sourceAuth: sigv4Auth,
      additionalOptions: {}
    },
    3,
    'Test sigv4 source cluster with no managedServiceSourceSnapshotEnabled, defaults to true'
  );

  testManagedServiceSourceSnapshot(
    {
      sourceAuth: sigv4Auth,
      additionalOptions: { managedServiceSourceSnapshotEnabled: false }
    },
    2,
    'Test sigv4 source cluster with false managedServiceSourceSnapshotEnabled, does not create snapshot role'
  );

  testManagedServiceSourceSnapshot(
    {
      sourceAuth: noAuth,
      additionalOptions: {}
    },
    2,
    'Test no auth source cluster with no managedServiceSourceSnapshotEnabled, defaults to false'
  );


  test('Test invalid engine version format throws error', () => {
    const contextOptions = {
      // Should be OS_1.3
      engineVersion: "OpenSearch_1.3"
    }

    const createStackFunc = () => createStackComposer(contextOptions)

    expect(createStackFunc).toThrow()
  })

  test('Test ES_7.10 engine version format is parsed', () => {
    const contextOptions = {
      engineVersion: "ES_7.10",
      sourceCluster: {
        disabled: true
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  test('Test OS 1.3 engine version format is parsed', () => {
    const contextOptions = {
      engineVersion: "OS_1.3",
      sourceCluster: {
        disabled: true
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  test('Test access policy is parsed for proper array format', () => {
    const contextOptions = {
      sourceCluster: {
        disabled: true
      },
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
      sourceCluster: {
        disabled: true
      },
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
      stage: "unit-test",
      sourceCluster: {
        disabled: true
      }
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
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      }
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

  test('Test that a context with no source cluster details succeeds if sourceCluster.disabled', () => {
    const sourceClusterDisabledContextOptions = {
      sourceCluster: {
        "disabled": true
      },
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true,

    }
    const openSearchStacks = createStackComposer(sourceClusterDisabledContextOptions)
    expect(openSearchStacks.stacks).toHaveLength(4)

    const sourceClusterNotExplicitlyDisabledContextOptions = {
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true
    }
    const sourceClusterNotExplicitlyDisabledCreateStackFunc = () => createStackComposerOnlyPassedContext(sourceClusterNotExplicitlyDisabledContextOptions)
    expect(sourceClusterNotExplicitlyDisabledCreateStackFunc).toThrow()

    const sourceClusterDisabledWithEndpointContextOptions = {
      sourceClusterDisabled: {
        "disabled": true,
        "endpoint": "XXXXXXXXXXXXXXXXXXXX"
      },
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true
    }
    const sourceClusterDisabledWithEndpointCreateStackFunc = () => createStackComposer(sourceClusterDisabledWithEndpointContextOptions)
    expect (sourceClusterDisabledWithEndpointCreateStackFunc).toThrow()
  })

  test('Test that a context with a snapshot block and sourceCluster block succeeds if sourceCluster is disabled and has version', () => {
    const contextOptions = {
      sourceCluster: {
        "disabled": true,
        "version": "ES_7.10"
      },
      snapshot: {
        "snapshotName": "my-snapshot-name",
        "snapshotRepoName": "my-snapshot-repo",
        "s3Uri": "s3://my-s3-bucket-name/my-bucket-path-to-snapshot-repo",
        "s3Region": "us-east-2"
      },
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true,

    }
    const openSearchStacks = createStackComposer(contextOptions)
    expect(openSearchStacks.stacks).toHaveLength(4)
  })

  test('Test that a context with a snapshot block and sourceCluster block fails if sourceCluster does not have version', () => {
    const contextOptions = {
      sourceCluster: {
        "disabled": true
      },
      snapshot: {
        "snapshotName": "my-snapshot-name",
        "snapshotRepoName": "my-snapshot-repo",
        "s3Uri": "s3://my-s3-bucket-name/my-bucket-path-to-snapshot-repo",
        "s3Region": "us-east-2"
      },
      otelCollectorEnabled: true,
      migrationAssistanceEnabled: true,
      vpcEnabled: true,
      migrationConsoleServiceEnabled: true,

    }
    const createStackComposerWithContextOptions = () => createStackComposer(contextOptions)
    expect (createStackComposerWithContextOptions).toThrow()
  })


  test('Test backwards compatibility of source/target cluster params', () => {
    // This is effectively a smoke test with the "old-style" flat source and target cluster parameters.
    const contextOptions = {
      vpcEnabled: true,
      migrationAssistanceEnabled: true,
      migrationConsoleServiceEnabled: true,
      sourceClusterEndpoint: "https://test-cluster",
      reindexFromSnapshotServiceEnabled: true,
      trafficReplayerServiceEnabled: true,
      nodeToNodeEncryptionEnabled: true, // required if FGAC is being used
      encryptionAtRestEnabled: true, // required if FGAC is being used
      enforceHTTPS: true // required if FGAC is being used
  }

    const stacks = createStackComposer(contextOptions)
    expect(stacks.stacks).toHaveLength(6)

  });
})
