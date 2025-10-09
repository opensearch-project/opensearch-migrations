import {ContainerImage} from "aws-cdk-lib/aws-ecs";
import {ClusterAuth, ClusterBasicAuth, ClusterNoAuth} from "../lib/common-utilities"
import {ClusterYaml, RFSBackfillYaml, ServicesYaml, SnapshotYaml} from "../lib/migration-services-yaml"
import {Template, Capture, Match} from "aws-cdk-lib/assertions";
import {MigrationConsoleStack} from "../lib/service-stacks/migration-console-stack";
import {createStackComposer} from "./test-utils";
import * as yaml from 'yaml';
import {describe, afterEach, beforeEach, test, expect, jest} from '@jest/globals';

describe('Migration Services YAML Tests', () => {
    beforeEach(() => {
        jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
    });

    afterEach(() => {
        jest.clearAllMocks();
        jest.resetModules();
        jest.restoreAllMocks();
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

        const basicAuth = new ClusterAuth({basicAuth: new ClusterBasicAuth("arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc")});
        const basicAuthDict = basicAuth.toDict();
        expect(basicAuthDict).toEqual({basic_auth: {user_secret_arn: "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc"}});
    })

    test('Test servicesYaml with target cluster can be stringified', () => {
        const servicesYaml = new ServicesYaml();

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
        const servicesYaml = new ServicesYaml();
        const targetCluster = new ClusterYaml({
            'endpoint': 'https://abc.com',
            auth: new ClusterAuth({noAuth: new ClusterNoAuth()})
        });
        servicesYaml.target_cluster = targetCluster;
        const userSecretArn = "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc";
        const basicAuth = new ClusterBasicAuth(userSecretArn);
        const sourceCluster = new ClusterYaml({
            'endpoint': 'https://xyz.com:9200',
            'auth': new ClusterAuth({basicAuth: basicAuth}),
        });
        servicesYaml.source_cluster = sourceCluster;

        expect(servicesYaml.target_cluster).toBeDefined();
        expect(servicesYaml.source_cluster).toBeDefined();
        const yaml = servicesYaml.stringify();
        const sourceClusterYaml = `source_cluster:\n  endpoint: ${sourceCluster.endpoint}\n  basic_auth:\n    user_secret_arn: ${userSecretArn}\n`
        expect(yaml).toBe(`${sourceClusterYaml}target_cluster:\n  endpoint: ${targetCluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  cloudwatch:\n`);
    });

    test('Test servicesYaml with rfs backfill can be stringified', () => {
        const clusterName = "migration-cluster-name";
        const serviceName = "rfs-service-name";
        const region = "us-east-1"
        const servicesYaml = new ServicesYaml();
        const rfsBackfillYaml = new RFSBackfillYaml();
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
        const servicesYaml = new ServicesYaml();
        const yaml = servicesYaml.stringify();
        expect(yaml).toBe(`metrics_source:\n  cloudwatch:\n`);
    });

    test('Test SnapshotYaml for filesystem only includes fs', () => {
        const fsSnapshot = new SnapshotYaml();
        fsSnapshot.fs = {"repo_path": "/path/to/shared/volume"}
        const fsSnapshotDict = fsSnapshot.toDict()
        expect(fsSnapshotDict).toBeDefined();
        expect(fsSnapshotDict).toHaveProperty("fs");
        expect(fsSnapshotDict["fs"]).toHaveProperty("repo_path");
        expect(fsSnapshotDict).not.toHaveProperty("s3");
    });

    test('Test SnapshotYaml for s3 only includes s3', () => {
        const s3Snapshot = new SnapshotYaml();
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
                "auth": {"type": "none"},
                "version": "ES_7.10"
            },
            reindexFromSnapshotServiceEnabled: true,
            trafficReplayerServiceEnabled: true,
            fineGrainedManagerUserSecretARN: "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc",
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
        expect(yamlFileContents).toContain(`user_secret_arn: ${contextOptions.fineGrainedManagerUserSecretARN}`)
        expect(yamlFileContents).toContain('metrics_source:\n  cloudwatch:')
        expect(yamlFileContents).toContain('kafka')
        // Validates that the file can be parsed as valid yaml and has the expected fields
        const parsedFromYaml = yaml.parse(yamlFileContents);
        // Validates that the file has the expected fields
        const expectedFields = ['source_cluster', 'target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka'];
        expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
        expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
    });

    test('Test that services yaml parameter is created by migration console stack with provided target domain and plaintext basic auth', () => {
        const contextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            sourceCluster: {
                "endpoint": "https://test-cluster",
                "auth": {
                    "type": "basic",
                    "username": "admin",
                    "password": "myStrongPassword123!"
                },
                "version": "ES_7.10"
            },
            targetCluster: {
                "endpoint": "https://target-cluster",
                "auth": {
                    "type": "basic",
                    "username": "admin",
                    "password": "myStrongPassword123!"
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
        const parsedFromYaml = yaml.parse(yamlFileContents);

        expect(parsedFromYaml.source_cluster).toBeDefined();
        expect(parsedFromYaml.source_cluster.basic_auth).toBeDefined();
        expect(parsedFromYaml.source_cluster.basic_auth.user_secret_arn).toBeDefined();
        expect(parsedFromYaml.source_cluster.basic_auth.username).toBeUndefined();
        expect(parsedFromYaml.source_cluster.basic_auth.password).toBeUndefined();

        expect(parsedFromYaml.target_cluster).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth.user_secret_arn).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth.username).toBeUndefined();
        expect(parsedFromYaml.target_cluster.basic_auth.password).toBeUndefined();

        expect(parsedFromYaml.metrics_source).toBeDefined();
        expect(parsedFromYaml.metrics_source.cloudwatch).toBeDefined();

        expect(parsedFromYaml.kafka).toBeDefined();

        // Validates that the file has the expected fields
        const expectedFields = ['source_cluster', 'target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka'];
        expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
        expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
    });

    test('Test that services yaml parameter is created by migration console stack with provided target domain and provided basic auth secret', () => {
        const contextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            sourceCluster: {
                "endpoint": "https://test-cluster",
                "auth": {
                    "type": "basic",
                    "userSecretArn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123cde",
                },
                "version": "ES_7.10"
            },
            targetCluster: {
                "endpoint": "https://target-cluster",
                "auth": {
                    "type": "basic",
                    "userSecretArn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc",
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
        const parsedFromYaml = yaml.parse(yamlFileContents);

        expect(parsedFromYaml.source_cluster).toBeDefined();
        expect(parsedFromYaml.source_cluster.basic_auth).toBeDefined();
        expect(parsedFromYaml.source_cluster.basic_auth.user_secret_arn).toBe(contextOptions.sourceCluster.auth.userSecretArn);

        expect(parsedFromYaml.target_cluster).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth.user_secret_arn).toBe(contextOptions.targetCluster.auth.userSecretArn);

        expect(parsedFromYaml.metrics_source).toBeDefined();
        expect(parsedFromYaml.metrics_source.cloudwatch).toBeDefined();

        expect(parsedFromYaml.kafka).toBeDefined();

        // Validates that the file has the expected fields
        const expectedFields = ['source_cluster', 'target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka'];
        expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
        expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
    });

    test('Test that services yaml parameter contains client_options when set', () => {
        const contextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            sourceCluster: {
                "endpoint": "https://test-cluster",
                "auth": {"type": "none"},
                "version": "ES_7.10"
            },
            targetCluster: {
                "endpoint": "https://target-cluster",
                "auth": {
                    "type": "basic",
                    "userSecretArn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc"
                }
            },
            reindexFromSnapshotServiceEnabled: true,
            trafficReplayerServiceEnabled: true,
        }
        const userAgent = "test-agent-v1.0"
        const stacks = createStackComposer(contextOptions, userAgent)

        const migrationConsoleStack: MigrationConsoleStack = (stacks.stacks.filter((s) => s instanceof MigrationConsoleStack)[0]) as MigrationConsoleStack
        const migrationConsoleStackTemplate = Template.fromStack(migrationConsoleStack)

        const valueCapture = new Capture();
        migrationConsoleStackTemplate.hasResourceProperties("AWS::SSM::Parameter", {
            Type: "String",
            Name: Match.stringLikeRegexp("/migration/.*/.*/servicesYamlFile"),
            Value: valueCapture,
        });
        console.error(valueCapture)
        const value = valueCapture.asObject()
        expect(value).toBeDefined();
        expect(value['Fn::Join']).toBeInstanceOf(Array);
        expect(value['Fn::Join'][1]).toBeInstanceOf(Array)
        // join the strings together to get the yaml file contents
        const yamlFileContents = value['Fn::Join'][1].join('')
        expect(yamlFileContents).toContain('source_cluster')
        expect(yamlFileContents).toContain('target_cluster')

        expect(yamlFileContents).toContain('basic_auth')
        expect(yamlFileContents).toContain(`user_secret_arn: ${contextOptions.targetCluster.auth.userSecretArn}`)
        expect(yamlFileContents).toContain('metrics_source:\n  cloudwatch:')
        expect(yamlFileContents).toContain('kafka')
        expect(yamlFileContents).toContain(`user_agent_extra: ${userAgent}`)
        // Validates that the file can be parsed as valid yaml and has the expected fields
        const parsedFromYaml = yaml.parse(yamlFileContents);
        // Validates that the file has the expected fields
        const expectedFields = ['source_cluster', 'target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka', 'client_options'];
        expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
        expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
    });

    test('Test that services yaml includes source version in metadata when source cluster is disabled', () => {
        const contextOptions = {
            vpcEnabled: true,
            migrationAssistanceEnabled: true,
            migrationConsoleServiceEnabled: true,
            sourceCluster: {
                "version": "ES_7.9",
                "disabled": true
            },
            targetCluster: {
                "endpoint": "https://target-cluster",
                "auth": {
                    "type": "basic",
                    "userSecretArn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc",
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
        const parsedFromYaml = yaml.parse(yamlFileContents);

        expect(parsedFromYaml.metadata_migration).toBeDefined();
        expect(parsedFromYaml.metadata_migration.source_cluster_version).toBe("ES_7.9")

        expect(parsedFromYaml.target_cluster).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth).toBeDefined();
        expect(parsedFromYaml.target_cluster.basic_auth.user_secret_arn).toBe(contextOptions.targetCluster.auth.userSecretArn);

        expect(parsedFromYaml.metrics_source).toBeDefined();
        expect(parsedFromYaml.metrics_source.cloudwatch).toBeDefined();

        expect(parsedFromYaml.kafka).toBeDefined();

        // Validates that the file has the expected fields
        const expectedFields = ['target_cluster', 'metrics_source', 'backfill', 'snapshot', 'metadata_migration', 'replay', 'kafka'];
        expect(Object.keys(parsedFromYaml).length).toEqual(expectedFields.length)
        expect(new Set(Object.keys(parsedFromYaml))).toEqual(new Set(expectedFields))
    });
});
