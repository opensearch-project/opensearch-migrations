import { ContainerImage } from "aws-cdk-lib/aws-ecs";
import { ClusterAuth, ClusterBasicAuth, ClusterNoAuth } from "../lib/common-utilities"
import { ClusterYaml, RFSBackfillYaml, ServicesYaml, SnapshotYaml } from "../lib/migration-services-yaml"
import { Template, Capture, Match } from "aws-cdk-lib/assertions";
import { MigrationConsoleStack } from "../lib/service-stacks/migration-console-stack";
import { createStackComposer } from "./test-utils";
import * as yaml from 'yaml';
import {describe, afterEach, beforeEach, test, expect, jest} from '@jest/globals';

jest.mock('aws-cdk-lib/aws-ecr-assets');
describe('Migration Services YAML Tests', () => {
  beforeEach(() => {
    jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
});

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
    jest.resetAllMocks();
  });

  test('Test default servicesYaml can be stringified', () => {
    const servicesYaml = new ServicesYaml();
    expect(servicesYaml.metrics_source).toBeDefined();
    expect(Object.keys(servicesYaml.metrics_source)).toContain("cloudwatch");
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe("metrics_source:\n  cloudwatch:\n");
  });

  test('Test ClusterAuth.toDict', () => {
    const clusterAuth = new ClusterAuth({noAuth: new ClusterNoAuth()});
    const dict = clusterAuth.toDict();
    expect(dict).toEqual({no_auth: ""});

    const basicAuth = new ClusterAuth({basicAuth: new ClusterBasicAuth({username: "XXX", password: "123"})});
    const basicAuthDict = basicAuth.toDict();
    expect(basicAuthDict).toEqual({basic_auth: {username: "XXX", password: "123"}});
  })

  test('Test servicesYaml with target cluster can be stringified', () => {
    let servicesYaml = new ServicesYaml();

    const cluster = new ClusterYaml({
      'endpoint': 'https://abc.com',
      auth: new ClusterAuth({noAuth: new ClusterNoAuth()})
    });
    servicesYaml.target_cluster = cluster;

    expect(servicesYaml.target_cluster).toBeDefined();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`target_cluster:\n  endpoint: ${cluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  cloudwatch:\n`);
  });

  test('Test servicesYaml with source and target cluster can be stringified', () => {
    let servicesYaml = new ServicesYaml();
    const targetCluster = new ClusterYaml({
      'endpoint': 'https://abc.com',
      auth: new ClusterAuth({noAuth: new ClusterNoAuth()})
    });
    servicesYaml.target_cluster = targetCluster;
    const sourceClusterUser = "abc";
    const sourceClusterPassword = "XXXXX";
    const basicAuth = new ClusterBasicAuth({ username: sourceClusterUser, password: sourceClusterPassword });
    const sourceCluster = new ClusterYaml({ 'endpoint': 'https://xyz.com:9200',
        'auth': new ClusterAuth({basicAuth: basicAuth}),
    });
    servicesYaml.source_cluster = sourceCluster;

    expect(servicesYaml.target_cluster).toBeDefined();
    expect(servicesYaml.source_cluster).toBeDefined();
    const yaml = servicesYaml.stringify();
    const sourceClusterYaml = `source_cluster:\n  endpoint: ${sourceCluster.endpoint}\n  basic_auth:\n    username: ${sourceClusterUser}\n    password: ${sourceClusterPassword}\n`
    expect(yaml).toBe(`${sourceClusterYaml}target_cluster:\n  endpoint: ${targetCluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  cloudwatch:\n`);
  });

  test('Test servicesYaml with rfs backfill can be stringified', () => {
    const clusterName = "migration-cluster-name";
    const serviceName = "rfs-service-name";
    const region = "us-east-1"
    let servicesYaml = new ServicesYaml();
    let rfsBackfillYaml = new RFSBackfillYaml();
    rfsBackfillYaml.ecs.cluster_name = clusterName;
    rfsBackfillYaml.ecs.service_name = serviceName;
    rfsBackfillYaml.ecs.aws_region = region;
    servicesYaml.backfill = rfsBackfillYaml;

    expect(servicesYaml.backfill).toBeDefined();
    expect(servicesYaml.backfill).toBeDefined();
    expect(servicesYaml.backfill instanceof RFSBackfillYaml).toBeTruthy();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`metrics_source:\n  cloudwatch:\nbackfill:\n  reindex_from_snapshot:\n    ecs:\n      cluster_name: ${clusterName}\n      service_name: ${serviceName}\n      aws_region: ${region}\n`);
  });

  test('Test servicesYaml without backfill does not include backend section', () => {
    let servicesYaml = new ServicesYaml();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`metrics_source:\n  cloudwatch:\n`);
  });

  test('Test SnapshotYaml for filesystem only includes fs', () => {
    let fsSnapshot = new SnapshotYaml();
    fsSnapshot.fs = {"repo_path": "/path/to/shared/volume"}
    const fsSnapshotDict = fsSnapshot.toDict()
    expect(fsSnapshotDict).toBeDefined();
    expect(fsSnapshotDict).toHaveProperty("fs");
    expect(fsSnapshotDict["fs"]).toHaveProperty("repo_path");
    expect(fsSnapshotDict).not.toHaveProperty("s3");
  });

  test('Test SnapshotYaml for s3 only includes s3', () => {
    let s3Snapshot = new SnapshotYaml();
    s3Snapshot.s3 = {"repo_uri": "s3://repo/path", "aws_region": "us-east-1"}
    const s3SnapshotDict = s3Snapshot.toDict()
    expect(s3SnapshotDict).toBeDefined();
    expect(s3SnapshotDict).toHaveProperty("s3");
    expect(s3SnapshotDict["s3"]).toHaveProperty("repo_uri");
    expect(s3SnapshotDict).not.toHaveProperty("fs");
  });

test('Test that services yaml parameter is created by migration console stack with target domain creation', () => {
    const contextOptions = {
        vpcEnabled: true,
        migrationAssistanceEnabled: true,
        migrationConsoleServiceEnabled: true,
        sourceCluster: {
          "endpoint": "https://test-cluster",
          "auth": {"type": "none"}
        },
        reindexFromSnapshotServiceEnabled: true,
        trafficReplayerServiceEnabled: true,
        fineGrainedManagerUserName: "admin",
        fineGrainedManagerUserSecretManagerKeyARN: "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc",
        nodeToNodeEncryptionEnabled: true, // required if FGAC is being used
        encryptionAtRestEnabled: true, // required if FGAC is being used
        enforceHTTPS: true // required if FGAC is being used
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

    expect(yamlFileContents).toContain('basic_auth')
    expect(yamlFileContents).toContain(`username: ${contextOptions.fineGrainedManagerUserName}`)
    expect(yamlFileContents).toContain(`password_from_secret_arn: ${contextOptions.fineGrainedManagerUserSecretManagerKeyARN}`)
    expect(yamlFileContents).toContain('metrics_source:\n  cloudwatch:')
    expect(yamlFileContents).toContain('kafka')
    // Validates that the file can be parsed as valid yaml and has the expected fields
    const parsedFromYaml = yaml.parse(yamlFileContents);
    // Validates that the file has the expected fields
    const expectedFields = ['source_cluster', 'target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka'];
    expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
    expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
  });


  test('Test that services yaml parameter is created by migration console stack with provided target domain', () => {
    const contextOptions = {
        vpcEnabled: true,
        migrationAssistanceEnabled: true,
        migrationConsoleServiceEnabled: true,
        sourceCluster: {
          "endpoint": "https://test-cluster",
          "auth": {"type": "none"}
        },
        targetCluster: {
          "endpoint": "https://target-cluster",
          "auth": {
            "type": "basic",
            "username": "admin",
            "passwordFromSecretArn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc"
          }
        },
        reindexFromSnapshotServiceEnabled: true,
        trafficReplayerServiceEnabled: true,
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

    expect(yamlFileContents).toContain('basic_auth')
    expect(yamlFileContents).toContain(`username: ${contextOptions.targetCluster.auth.username}`)
    expect(yamlFileContents).toContain(`password_from_secret_arn: ${contextOptions.targetCluster.auth.passwordFromSecretArn}`)
    expect(yamlFileContents).toContain('metrics_source:\n  cloudwatch:')
    expect(yamlFileContents).toContain('kafka')
    // Validates that the file can be parsed as valid yaml and has the expected fields
    const parsedFromYaml = yaml.parse(yamlFileContents);
    // Validates that the file has the expected fields
    const expectedFields = ['source_cluster', 'target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka'];
    expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
    expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
  });
});
