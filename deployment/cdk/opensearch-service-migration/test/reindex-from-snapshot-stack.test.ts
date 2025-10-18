import { Template } from 'aws-cdk-lib/assertions';
import { ContainerImage } from 'aws-cdk-lib/aws-ecs';
import { StringParameter } from 'aws-cdk-lib/aws-ssm';
import { ReindexFromSnapshotStack } from '../lib/service-stacks/reindex-from-snapshot-stack';
import { createStackComposer } from './test-utils';
import { describe, beforeEach, afterEach, test, expect, jest } from '@jest/globals';


describe('ReindexFromSnapshotStack Tests', () => {
  beforeEach(() => {
    jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
    jest.spyOn(StringParameter, 'valueForStringParameter').mockImplementation((scope, paramName) => {
      switch (paramName) {
        case '/migration/unit-test/source-cluster-endpoint':
          return 'https://source-cluster-endpoint';
        case '/migration/unit-test/service-security-group-id':
          return 'sg-12345';
        case '/migration/unit-test/os-access-security-group-id':
          return 'sg-67890';
        case '/migration/unit-test/os-cluster-endpoint':
          return 'https://os-cluster-endpoint';
        case '/migration/unit-test/artifact-s3-arn':
          return 'arn:aws:s3:::migration-artifacts-bucket';
        default:
          return 'mock-value';
      }
    });
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
  });

  test('ReindexFromSnapshotStack creates expected resources', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true,
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      Family: 'migration-unit-test-reindex-from-snapshot',
    });

    template.hasResourceProperties('AWS::ECS::Service', {
      ServiceName: 'migration-unit-test-reindex-from-snapshot',
    });

    template.hasResourceProperties('AWS::IAM::Role', {
      AssumeRolePolicyDocument: {
        Statement: [
          {
            Action: 'sts:AssumeRole',
            Effect: 'Allow',
            Principal: {
              Service: 'ecs-tasks.amazonaws.com',
            },
          },
        ],
      }
    });
    // Assert CPU configuration
    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      Cpu: "4096",
      Memory: "8192",
    });
  });

  test('ReindexFromSnapshotStack sets correct RFS command', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true,
      nodeToNodeEncryptionEnabled: true,
      encryptionAtRestEnabled: true,
      enforceHTTPS: true
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo\" --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 94489280512 --max-connections 20 --initial-lease-duration PT60M --source-version \"ES_7.10\""
            ],
          ],
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });

  test('ReindexFromSnapshotStack sets correct command for sigv4 auth', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      targetCluster: {
        "endpoint": "https://target-cluster",
        "auth": {"type": "sigv4", "region": "eu-west-1", "serviceSigningName": "aoss"}
      },
      migrationAssistanceEnabled: true,
    };


    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo\" --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 94489280512 --max-connections 20 --initial-lease-duration PT60M --target-aws-service-signing-name aoss --target-aws-region eu-west-1 --source-version \"ES_7.10\"",
            ],
          ],
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });

  test('ReindexFromSnapshotStack sets correct RFS command from custom SnapshotYaml', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      snapshot: {
        "snapshotName": "test-snapshot",
        "snapshotRepoName": "test-repo",
        "s3Uri": "s3://snapshot-bucket-123456789012-us-east-2/snapshot-repo",
        "s3Region": "us-east-2"
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true,
      nodeToNodeEncryptionEnabled: true,
      encryptionAtRestEnabled: true,
      enforceHTTPS: true
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://snapshot-bucket-123456789012-us-east-2/snapshot-repo\" --s3-region us-east-2 --snapshot-name test-snapshot --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 94489280512 --max-connections 20 --initial-lease-duration PT60M --source-version \"ES_7.10\""
            ],
          ],
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });

  test('ReindexFromSnapshotStack sets correct YAML configurations', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      migrationAssistanceEnabled: true,
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();

    expect(reindexStack.rfsBackfillYaml.ecs.cluster_name).toBe('migration-unit-test-ecs-cluster');
    expect(reindexStack.rfsBackfillYaml.ecs.service_name).toBe('migration-unit-test-reindex-from-snapshot');
  });

  test('ReindexFromSnapshotStack correctly overrides with extraArgs', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      reindexFromSnapshotExtraArgs: '--custom-arg value --flag --snapshot-name "custom-snapshot"',
      migrationAssistanceEnabled: true,
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo\" --s3-region us-east-1 --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 94489280512 --max-connections 20 --initial-lease-duration PT60M --source-version \"ES_7.10\" --custom-arg value --flag --snapshot-name \"custom-snapshot\""
            ]
          ]
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });

  test('ReindexFromSnapshotStack with maximum worker size', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      reindexFromSnapshotWorkerSize: "maximum",
      migrationAssistanceEnabled: true,
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo\" --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 94489280512 --max-connections 100 --initial-lease-duration PT60M --source-version \"ES_7.10\"",
            ],
          ],
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);

    // Assert CPU configuration
    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      Cpu: "16384",
      Memory: "32768",
    });
      // Find the ECS service resource
      const services = template.findResources('AWS::ECS::Service');
      const serviceKeys = Object.keys(services);
      expect(serviceKeys.length).toBe(1);
      
      const service = services[serviceKeys[0]];
      const volumes = service.Properties.VolumeConfigurations;
      expect(volumes).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            ManagedEBSVolume: expect.objectContaining({
              Encrypted: true,
              SizeInGiB: 194,
              Throughput: 450,
            }),
          }),
        ])
      );
      // Check volumes directly from the task definition
      const taskVolumes = taskDefinition.Properties.Volumes;
      // Ensure there are 2 volumes, ebs and ephemeral
      expect(taskVolumes.length).toBe(2);
    });

  test('ReindexFromSnapshotStack configures ephemeral storage in GovCloud', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      migrationAssistanceEnabled: true,
    };
    const stacks = createStackComposer(contextOptions, undefined, 'us-gov-west-1');
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    expect(reindexStack.region).toEqual("us-gov-west-1");
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);

    // Check ephemeral storage directly from the task definition
    const ephemeralStorage = taskDefinition.Properties.EphemeralStorage;
    expect(ephemeralStorage.SizeInGiB).toBe(199);

    // Check volumes directly from the task definition
    const taskVolumes = taskDefinition.Properties.Volumes;
    // Ensure the only volume is the ephemeral storage
    expect(taskVolumes.length).toBe(1);
  });

  test('ReindexFromSnapshotStack uses ceiling of maxShardSizeBytes calculation', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      targetCluster: {
        "endpoint": "https://target-cluster",
        "auth": {"type": "sigv4", "region": "eu-west-1", "serviceSigningName": "aoss"}
      },
      migrationAssistanceEnabled: true,
      reindexFromSnapshotMaxShardSizeGiB: 1.000001
    };


    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);
    
    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo\" --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 1181117188 --max-connections 20 --initial-lease-duration PT60M --target-aws-service-signing-name aoss --target-aws-region eu-west-1 --source-version \"ES_7.10\"",
            ],
          ],
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });


  test('ReindexFromSnapshotStack throws error for large shard size in GovCloud', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      reindexFromSnapshotMaxShardSizeGiB: 81, // Exceeding the limit
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      },
      migrationAssistanceEnabled: true,
    };

    expect(() => createStackComposer(contextOptions, undefined, 'us-gov-west-1')).toThrow(
      /Your max shard size of 81 GiB is too large to migrate in GovCloud, the max supported is 80 GiB/
    );
  });

  test('ReindexFromSnapshotStack sets correct RFS command when source cluster is disabled', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "disabled": true,
        "version": "ES_7.9"
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true,
      nodeToNodeEncryptionEnabled: true,
      encryptionAtRestEnabled: true,
      enforceHTTPS: true
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);

    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    expect(containerDefinitions[0].Command).toEqual([
      '/bin/sh',
      '-c',
      '/rfs-app/entrypoint.sh'
    ]);
    expect(containerDefinitions[0].Environment).toEqual([
      {
        Name: 'RFS_COMMAND',
        Value: {
          "Fn::Join": [
            "",
            [ "/rfs-app/runJavaWithClasspath.sh org.opensearch.migrations.RfsMigrateDocuments --target-insecure --s3-local-dir \"/storage/s3_files\" --s3-repo-uri \"s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo\" --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir \"/storage/lucene\" --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --max-shard-size-bytes 94489280512 --max-connections 20 --initial-lease-duration PT60M --source-version \"ES_7.9\""
            ],
          ],
        }
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });

  test('ReindexFromSnapshotStack fails when source cluster version is missing with external snapshot', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "disabled": true
        // Missing version field
      },
      snapshot: {
        "snapshotName": "test-snapshot",
        "snapshotRepoName": "test-repo",
        "s3Uri": "s3://snapshot-bucket/snapshot-repo",
        "s3Region": "us-east-1"
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true
    };

    expect(() => createStackComposer(contextOptions)).toThrow(
      /The `sourceCluster` object must be provided with a `version` field when using an external snapshot/
    );
  });

  test('ReindexFromSnapshotStack omits source-version parameter when source cluster version is missing', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "disabled": true
        // Missing version field - should result in --source-version being omitted
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    // Find the task definition resource
    const taskDefinitions = template.findResources('AWS::ECS::TaskDefinition');
    const taskDefinitionKeys = Object.keys(taskDefinitions);
    expect(taskDefinitionKeys.length).toBe(1);

    const taskDefinition = taskDefinitions[taskDefinitionKeys[0]];
    const containerDefinitions = taskDefinition.Properties.ContainerDefinitions;
    expect(containerDefinitions.length).toBe(1);
    
    // Verify that the RFS command does NOT include --source-version parameter
    // This will cause the Java application to fail at runtime with JCommander error
    const rfsCommand = containerDefinitions[0].Environment.find((env: {Name: string, Value: unknown}) => env.Name === 'RFS_COMMAND');
    expect(rfsCommand).toBeDefined();
    
    // The command should NOT include --source-version, which will cause RFS to fail with required parameter error
    const commandValue = rfsCommand.Value['Fn::Join'][1].join('');
    expect(commandValue).not.toContain('--source-version');
    
    // This demonstrates that RFS will fail at runtime with:
    // "The following option is required: [--source-version, --sourceVersion]"
  });
});
