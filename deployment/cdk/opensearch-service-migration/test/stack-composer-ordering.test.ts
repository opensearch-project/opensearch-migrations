import {createStackComposer} from "./test-utils";
import {Match, Template} from "aws-cdk-lib/assertions";
import {CaptureProxyStack} from "../lib/service-stacks/capture-proxy-stack";
import {ElasticsearchStack} from "../lib/service-stacks/elasticsearch-stack";
import {TrafficReplayerStack} from "../lib/service-stacks/traffic-replayer-stack";
import {MigrationConsoleStack} from "../lib/service-stacks/migration-console-stack";
import {KafkaStack} from "../lib/service-stacks/kafka-stack";
import {MigrationAssistanceStack} from "../lib/migration-assistance-stack";
import {ContainerImage} from "aws-cdk-lib/aws-ecs";
import {ReindexFromSnapshotStack} from "../lib/service-stacks/reindex-from-snapshot-stack";
import {describe, beforeEach, afterEach, test, expect, jest} from '@jest/globals';

describe('Stack Composer Ordering Tests', () => {
    beforeEach(() => {
        jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
    });

    afterEach(() => {
        jest.clearAllMocks();
        jest.resetModules();
        jest.restoreAllMocks();
    });

    test('Test all migration services with MSK get created when enabled', () => {
        const contextOptions = {
            "stage": "test",
            "engineVersion": "OS_2.9",
            "domainName": "unit-test-opensearch-cluster",
            "dataNodeCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "vpcEnabled": true,
            "migrationAssistanceEnabled": true,
            "migrationConsoleServiceEnabled": true,
            "trafficReplayerServiceEnabled": true,
            "captureProxyServiceEnabled": true,
            "captureProxyDesiredCount": 2,
            "targetClusterProxyServiceEnabled": true,
            "targetClusterProxyDesiredCount": 2,
            "elasticsearchServiceEnabled": true,
            "otelCollectorEnabled": true,
            "reindexFromSnapshotServiceEnabled": true
        }

        const stacks = createStackComposer(contextOptions)

        const services = [CaptureProxyStack, ElasticsearchStack, MigrationConsoleStack,
            TrafficReplayerStack, ReindexFromSnapshotStack]
        services.forEach((stackClass) => {
            const stack = stacks.stacks.filter((s) => s instanceof stackClass)[0]
            const template = Template.fromStack(stack)
            try {
                template.resourceCountIs("AWS::ECS::Service", 1)
            } catch (error) {
                console.error(`Validation failed for stack: ${stackClass.name}`, error)
                throw error
            }
        })

        const migrationStack = stacks.stacks.find((s) => s instanceof MigrationAssistanceStack) as MigrationAssistanceStack
        const migrationTemplate = Template.fromStack(migrationStack)
        migrationTemplate.hasResourceProperties("AWS::MSK::Configuration", {
            ServerProperties: [
                "auto.create.topics.enable=true",
                "num.partitions=3",
                "log.message.timestamp.type=LogAppendTime"
            ].join("\n")
        })
        migrationTemplate.hasResourceProperties("AWS::MSK::Cluster", {
            ConfigurationInfo: {
                Arn: Match.anyValue(),
                Revision: {
                    "Fn::GetAtt": [
                        Match.stringLikeRegexp("migrationMSKClusterConfig"),
                        "LatestRevision.Revision"
                    ]
                }
            }
        })

        const captureProxyStack = stacks.stacks.find((s) => s instanceof CaptureProxyStack) as CaptureProxyStack
        Template.fromStack(captureProxyStack).hasResourceProperties("AWS::ECS::Service", {
            DesiredCount: 2,
            DeploymentConfiguration: {
                MinimumHealthyPercent: 100,
                MaximumPercent: 150
            }
        })
        Template.fromStack(captureProxyStack).hasResourceProperties("AWS::ECS::TaskDefinition", {
            ContainerDefinitions: Match.arrayWith([
                Match.objectLike({
                    Name: "capture-proxy",
                    Command: Match.arrayWith([
                        "--minimumKafkaTopicPartitions",
                        "3"
                    ])
                })
            ])
        })
        const targetProxyStack = stacks.stacks.find(
            (s) => s instanceof CaptureProxyStack && s.stackName.endsWith("-TargetClusterProxy")
        ) as CaptureProxyStack
        Template.fromStack(targetProxyStack).hasResourceProperties("AWS::ECS::Service", {
            DesiredCount: 2,
            DeploymentConfiguration: {
                MinimumHealthyPercent: 50,
                MaximumPercent: 200
            }
        })
        const captureProxyPolicies = Template.fromStack(captureProxyStack).findResources("AWS::IAM::Policy")
        const captureProxyPolicyStatements = Object.values(captureProxyPolicies as Record<string, {
            Properties: {PolicyDocument: {Statement: unknown[]}}
        }>).flatMap((policy) => policy.Properties.PolicyDocument.Statement)
        expect(captureProxyPolicyStatements).toEqual(expect.arrayContaining([
            expect.objectContaining({
                Action: [
                    "kafka-cluster:AlterGroup",
                    "kafka-cluster:DescribeGroup"
                ],
                Effect: "Allow",
                Resource: expect.objectContaining({
                    "Fn::Join": expect.arrayContaining([
                        expect.arrayContaining([
                            expect.stringContaining(":group/")
                        ])
                    ])
                })
            }),
            expect.objectContaining({
                Action: expect.arrayContaining([
                    "kafka-cluster:CreateTopic",
                    "kafka-cluster:DescribeTopic",
                    "kafka-cluster:AlterTopic",
                    "kafka-cluster:WriteData"
                ]),
                Effect: "Allow",
                Resource: expect.objectContaining({
                    "Fn::Join": expect.arrayContaining([
                        expect.arrayContaining([
                            expect.stringContaining(":topic/")
                        ])
                    ])
                })
            })
        ]))
        expect(
            JSON.stringify(Template.fromStack(targetProxyStack).findResources("AWS::ECS::TaskDefinition"))
        ).not.toContain("--minimumKafkaTopicPartitions")
    })

    test('Test all migration services with Kafka container get created when enabled', () => {
        const contextOptions = {
            "stage": "test",
            "engineVersion": "OS_2.9",
            "domainName": "unit-test-opensearch-cluster",
            "dataNodeCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "vpcEnabled": true,
            "migrationAssistanceEnabled": true,
            "migrationConsoleServiceEnabled": true,
            "trafficReplayerServiceEnabled": true,
            "captureProxyServiceEnabled": true,
            "captureProxyDesiredCount": 2,
            "elasticsearchServiceEnabled": true,
            "kafkaBrokerServiceEnabled": true,
            "otelCollectorEnabled": true,
            "reindexFromSnapshotServiceEnabled": true
        }

        const stacks = createStackComposer(contextOptions)

        const services = [CaptureProxyStack, ElasticsearchStack, MigrationConsoleStack,
            TrafficReplayerStack, KafkaStack, ReindexFromSnapshotStack]
        services.forEach((stackClass) => {
            const stack = stacks.stacks.filter((s) => s instanceof stackClass)[0]
            const template = Template.fromStack(stack)
            try {
                template.resourceCountIs("AWS::ECS::Service", 1)
            } catch (error) {
                console.error(`Validation failed for stack: ${stackClass.name}`, error)
                throw error
            }
        })

        const kafkaStack = stacks.stacks.find((s) => s instanceof KafkaStack) as KafkaStack
        Template.fromStack(kafkaStack).hasResourceProperties("AWS::ECS::TaskDefinition", {
            ContainerDefinitions: Match.arrayWith([
                Match.objectLike({
                    Environment: Match.arrayWith([
                        {
                            Name: "KAFKA_NUM_PARTITIONS",
                            Value: "3"
                        },
                        {
                            Name: "KAFKA_LOG_MESSAGE_TIMESTAMP_TYPE",
                            Value: "LogAppendTime"
                        }
                    ])
                })
            ])
        })
    })

    test('Test no migration services get deployed when disabled', () => {
        const contextOptions = {
            "stage": "test",
            "engineVersion": "OS_2.9",
            "domainName": "unit-test-opensearch-cluster",
            "dataNodeCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "vpcEnabled": true,
            "migrationAssistanceEnabled": true,
            "migrationConsoleServiceEnabled": false,
            "trafficReplayerServiceEnabled": false,
            "captureProxyServiceEnabled": false,
            "elasticsearchServiceEnabled": false,
            "kafkaBrokerServiceEnabled": false,
            "otelCollectorEnabled": false,
            "reindexFromSnapshotServiceEnabled": false,
            "sourceCluster": {
                "endpoint": "https://test-cluster",
                "auth": {"type": "none"},
                "version": "ES_7.10"
            }
        }

        const stacks = createStackComposer(contextOptions)

        const services = [CaptureProxyStack, ElasticsearchStack, MigrationConsoleStack,
            TrafficReplayerStack, KafkaStack, ReindexFromSnapshotStack]
        services.forEach( (stackClass) => {
            const stack = stacks.stacks.filter((s) => s instanceof stackClass)[0]
            expect(stack).toBeUndefined()
        })
    })
})
