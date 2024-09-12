import { Template, Capture } from 'aws-cdk-lib/assertions';
import { ContainerImage } from 'aws-cdk-lib/aws-ecs';
import { StringParameter } from 'aws-cdk-lib/aws-ssm';
import { ReindexFromSnapshotStack } from '../lib/service-stacks/reindex-from-snapshot-stack';
import { createStackComposer } from './test-utils';
import { describe, beforeEach, afterEach, test, expect, jest } from '@jest/globals';

jest.mock('aws-cdk-lib/aws-ecr-assets');

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
    jest.resetAllMocks();
  });

  test('ReindexFromSnapshotStack creates expected resources', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"}
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
  });

  test('ReindexFromSnapshotStack sets correct RFS command', () => {
    const contextOptions = {
      vpcEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"}
      },
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      migrationAssistanceEnabled: true,
      fineGrainedManagerUserName: "test-user",
      fineGrainedManagerUserSecretManagerKeyARN: "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-secret",
      nodeToNodeEncryptionEnabled: true,
      encryptionAtRestEnabled: true,
      enforceHTTPS: true
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    const taskDefinitionCapture = new Capture();
    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: taskDefinitionCapture,
    });

    const containerDefinitions = taskDefinitionCapture.asArray();
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
            [ "/rfs-app/runJavaWithClasspath.sh com.rfs.RfsMigrateDocuments --s3-local-dir /tmp/s3_files --s3-repo-uri s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir '/lucene' --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
            ],
          ],
        }
      },
      {
        Name: 'RFS_TARGET_USER',
        Value: 'test-user'
      },
      {
        Name: 'RFS_TARGET_PASSWORD',
        Value: ''
      },
      {
        Name: 'RFS_TARGET_PASSWORD_ARN',
        Value: 'arn:aws:secretsmanager:us-east-1:123456789012:secret:test-secret'
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
        "auth": {"type": "none"}
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

    const taskDefinitionCapture = new Capture();
    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: taskDefinitionCapture,
    });

    const containerDefinitions = taskDefinitionCapture.asArray();
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
            [ "/rfs-app/runJavaWithClasspath.sh com.rfs.RfsMigrateDocuments --s3-local-dir /tmp/s3_files --s3-repo-uri s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo --s3-region us-east-1 --snapshot-name rfs-snapshot --lucene-dir '/lucene' --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              "--target-aws-service-signing-name aoss --target-aws-region eu-west-1",
            ],
          ],
        }
      },
      {
        Name: 'RFS_TARGET_USER',
        Value: ''
      },
      {
        Name: 'RFS_TARGET_PASSWORD',
        Value: ''
      },
      {
        Name: 'RFS_TARGET_PASSWORD_ARN',
        Value: ''
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
        "auth": {"type": "none"}
      },
      migrationAssistanceEnabled: true,
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();

    expect(reindexStack.rfsBackfillYaml.ecs.cluster_name).toBe('migration-unit-test-ecs-cluster');
    expect(reindexStack.rfsBackfillYaml.ecs.service_name).toBe('migration-unit-test-reindex-from-snapshot');
    expect(reindexStack.rfsSnapshotYaml.s3).toEqual({
      repo_uri: expect.stringMatching(/s3:\/\/migration-artifacts-.*-unit-test-.*/),
      aws_region: expect.any(String),
    });
    expect(reindexStack.rfsSnapshotYaml.snapshot_name).toBe('rfs-snapshot');
  });

  test('ReindexFromSnapshotStack correctly merges extraArgs', () => {
    const contextOptions = {
      vpcEnabled: true,
      reindexFromSnapshotServiceEnabled: true,
      stage: 'unit-test',
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"}
      },
      reindexFromSnapshotExtraArgs: '--custom-arg value --flag --snapshot-name custom-snapshot',
      migrationAssistanceEnabled: true,
    };

    const stacks = createStackComposer(contextOptions);
    const reindexStack = stacks.stacks.find(s => s instanceof ReindexFromSnapshotStack) as ReindexFromSnapshotStack;
    expect(reindexStack).toBeDefined();
    const template = Template.fromStack(reindexStack);

    const taskDefinitionCapture = new Capture();
    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: taskDefinitionCapture,
    });

    const containerDefinitions = taskDefinitionCapture.asArray();
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
            [ "/rfs-app/runJavaWithClasspath.sh com.rfs.RfsMigrateDocuments --s3-local-dir /tmp/s3_files --s3-repo-uri s3://migration-artifacts-test-account-unit-test-us-east-1/rfs-snapshot-repo --s3-region us-east-1 --snapshot-name custom-snapshot --lucene-dir /lucene --target-host ",
              {
                "Ref": "SsmParameterValuemigrationunittestdefaultosClusterEndpointC96584B6F00A464EAD1953AFF4B05118Parameter",
              },
              " --custom-arg value --flag"
            ]
          ]
        }
      },
      {
        Name: 'RFS_TARGET_USER',
        Value: ''
      },
      {
        Name: 'RFS_TARGET_PASSWORD',
        Value: ''
      },
      {
        Name: 'RFS_TARGET_PASSWORD_ARN',
        Value: ''
      },
      {
        Name: 'SHARED_LOGS_DIR_PATH',
        Value: '/shared-logs-output/reindex-from-snapshot-default'
      }
    ]);
  });
});
