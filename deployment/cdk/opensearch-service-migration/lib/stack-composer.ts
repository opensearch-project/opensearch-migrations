import {Construct} from "constructs";
import {Duration, Stack, StackProps} from "aws-cdk-lib";
import {readFileSync} from 'fs';
import {OpenSearchDomainStack} from "./opensearch-domain-stack";
import {EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import * as defaultValuesJson from "../default-values.json"
import {NetworkStack} from "./network-stack";
import {MigrationAssistanceStack} from "./migration-assistance-stack";
import {FetchMigrationStack} from "./fetch-migration-stack";
import {MSKUtilityStack} from "./msk-utility-stack";
import {MigrationConsoleStack} from "./service-stacks/migration-console-stack";
import {CaptureProxyESStack} from "./service-stacks/capture-proxy-es-stack";
import {TrafficReplayerStack} from "./service-stacks/traffic-replayer-stack";
import {CaptureProxyStack} from "./service-stacks/capture-proxy-stack";
import {ElasticsearchStack} from "./service-stacks/elasticsearch-stack";
import {KafkaStack} from "./service-stacks/kafka-stack";
import {Application} from "@aws-cdk/aws-servicecatalogappregistry-alpha";
import {OpenSearchContainerStack} from "./service-stacks/opensearch-container-stack";
import {determineStreamingSourceType, StreamingSourceType} from "./streaming-source-type";
import {parseRemovalPolicy, validateFargateCpuArch} from "./common-utilities";
import {ReindexFromSnapshotStack} from "./service-stacks/reindex-from-snapshot-stack";
import { ServicesYaml } from "./migration-services-yaml";

export interface StackPropsExt extends StackProps {
    readonly stage: string,
    readonly defaultDeployId: string,
    readonly addOnMigrationDeployId?: string
}

export interface StackComposerProps extends StackProps {
    readonly migrationsSolutionVersion: string
    readonly migrationsAppRegistryARN?: string,
    readonly customReplayerUserAgent?: string
}

export class StackComposer {
    public stacks: Stack[] = [];

    private addStacksToAppRegistry(appRegistryAppARN: string, allStacks: Stack[]) {
        for (let stack of allStacks) {
            const appRegistryApp = Application.fromApplicationArn(stack, 'AppRegistryApplicationImport', appRegistryAppARN)
            appRegistryApp.associateApplicationWithStack(stack)
        }
    }

    private getContextForType(optionName: string, expectedType: string, defaultValues: { [x: string]: (any); }, contextJSON: { [x: string]: (any); }): any {
        const option = contextJSON[optionName]

        // If no context is provided (undefined or empty string) and a default value exists, use it
        if ((option === undefined || option === "") && defaultValues[optionName]) {
            return defaultValues[optionName]
        }

        // Filter out invalid or missing options by setting undefined (empty strings, null, undefined, NaN)
        if (option !== false && option !== 0 && !option) {
            return undefined
        }
        // Values provided by the CLI will always be represented as a string and need to be parsed
        if (typeof option === 'string') {
            if (expectedType === 'number') {
                return parseInt(option)
            }
            if (expectedType === 'boolean' || expectedType === 'object') {
                try {
                    return JSON.parse(option)
                } catch (e) {
                    if (e instanceof SyntaxError) {
                        console.error(`Unable to parse option: ${optionName} with expected type: ${expectedType}`)
                    }
                    throw e
                }
            }
        }
        // Values provided by the cdk.context.json should be of the desired type
        if (typeof option !== expectedType) {
            throw new Error(`Type provided by cdk.context.json for ${optionName} was ${typeof option} but expected ${expectedType}`)
        }
        return option
    }

    private getEngineVersion(engineVersionString: string) : EngineVersion {
        let version: EngineVersion
        if (engineVersionString && engineVersionString.startsWith("OS_")) {
            // Will accept a period delimited version string (i.e. 1.3) and return a proper EngineVersion
            version = EngineVersion.openSearch(engineVersionString.substring(3))
        } else if (engineVersionString && engineVersionString.startsWith("ES_")) {
            version = EngineVersion.elasticsearch(engineVersionString.substring(3))
        } else {
            throw new Error("Engine version is not present or does not match the expected format, i.e. OS_1.3 or ES_7.9")
        }
        return version
    }

    private addDependentStacks(primaryStack: Stack, dependantStacks: any[]) {
        for (let stack of dependantStacks) {
            if (stack) {
                primaryStack.addDependency(stack)
            }
        }
    }

    private parseContextBlock(scope: Construct, contextId: string) {
        const contextFile = scope.node.tryGetContext("contextFile")
        if (contextFile) {
            const fileString = readFileSync(contextFile, 'utf-8');
            let fileJSON
            try {
                fileJSON = JSON.parse(fileString)
            } catch (error) {
                throw new Error(`Unable to parse context file ${contextFile} into JSON with following error: ${error}`);
            }
            const contextBlock = fileJSON[contextId]
            if (!contextBlock) {
                throw new Error(`No CDK context block found for contextId '${contextId}' in file ${contextFile}`)
            }
            return contextBlock
        }

        let contextJSON = scope.node.tryGetContext(contextId)
        if (!contextJSON) {
            throw new Error(`No CDK context block found for contextId '${contextId}'`)
        }
        // For a context block to be provided as a string (as in the case of providing via command line) it will need to be properly escaped
        // to be captured. This requires JSON to parse twice, 1. Returns a normal JSON string with no escaping 2. Returns a JSON object for use
        if (typeof contextJSON === 'string') {
            contextJSON = JSON.parse(JSON.parse(contextJSON))
        }
        return contextJSON

    }

    constructor(scope: Construct, props: StackComposerProps) {

        const defaultValues: { [x: string]: (any); } = defaultValuesJson
        const region = props.env?.region
        const defaultDeployId = 'default'

        const contextId = scope.node.tryGetContext("contextId")
        if (!contextId) {
            throw new Error("Required context field 'contextId' not provided")
        }
        const contextJSON = this.parseContextBlock(scope, contextId)
        console.log('Received following context block for deployment: ')
        console.log(contextJSON)
        console.log('End of context block.')

        const stage = this.getContextForType('stage', 'string', defaultValues, contextJSON)

        let version: EngineVersion

        const domainName = this.getContextForType('domainName', 'string', defaultValues, contextJSON)
        const domainAZCount = this.getContextForType('domainAZCount', 'number', defaultValues, contextJSON)
        const dataNodeType = this.getContextForType('dataNodeType', 'string', defaultValues, contextJSON)
        const dataNodeCount = this.getContextForType('dataNodeCount', 'number', defaultValues, contextJSON)
        const dedicatedManagerNodeType = this.getContextForType('dedicatedManagerNodeType', 'string', defaultValues, contextJSON)
        const dedicatedManagerNodeCount = this.getContextForType('dedicatedManagerNodeCount', 'number', defaultValues, contextJSON)
        const warmNodeType = this.getContextForType('warmNodeType', 'string', defaultValues, contextJSON)
        const warmNodeCount = this.getContextForType('warmNodeCount', 'number', defaultValues, contextJSON)
        const useUnsignedBasicAuth = this.getContextForType('useUnsignedBasicAuth', 'boolean', defaultValues, contextJSON)
        const fineGrainedManagerUserARN = this.getContextForType('fineGrainedManagerUserARN', 'string', defaultValues, contextJSON)
        const fineGrainedManagerUserName = this.getContextForType('fineGrainedManagerUserName', 'string', defaultValues, contextJSON)
        const fineGrainedManagerUserSecretManagerKeyARN = this.getContextForType('fineGrainedManagerUserSecretManagerKeyARN', 'string', defaultValues, contextJSON)
        const enableDemoAdmin = this.getContextForType('enableDemoAdmin', 'boolean', defaultValues, contextJSON)
        const enforceHTTPS = this.getContextForType('enforceHTTPS', 'boolean', defaultValues, contextJSON)
        const ebsEnabled = this.getContextForType('ebsEnabled', 'boolean', defaultValues, contextJSON)
        const ebsIops = this.getContextForType('ebsIops', 'number', defaultValues, contextJSON)
        const ebsVolumeTypeName = this.getContextForType('ebsVolumeType', 'string', defaultValues, contextJSON)
        const ebsVolumeSize = this.getContextForType('ebsVolumeSize', 'number', defaultValues, contextJSON)
        const encryptionAtRestEnabled = this.getContextForType('encryptionAtRestEnabled', 'boolean', defaultValues, contextJSON)
        const encryptionAtRestKmsKeyARN = this.getContextForType("encryptionAtRestKmsKeyARN", 'string', defaultValues, contextJSON)
        const loggingAppLogEnabled = this.getContextForType('loggingAppLogEnabled', 'boolean', defaultValues, contextJSON)
        const loggingAppLogGroupARN = this.getContextForType('loggingAppLogGroupARN', 'string', defaultValues, contextJSON)
        const noneToNodeEncryptionEnabled = this.getContextForType('nodeToNodeEncryptionEnabled', 'boolean', defaultValues, contextJSON)
        const vpcId = this.getContextForType('vpcId', 'string', defaultValues, contextJSON)
        const vpcEnabled = this.getContextForType('vpcEnabled', 'boolean', defaultValues, contextJSON)
        const vpcSecurityGroupIds = this.getContextForType('vpcSecurityGroupIds', 'object', defaultValues, contextJSON)
        const vpcSubnetIds = this.getContextForType('vpcSubnetIds', 'object', defaultValues, contextJSON)
        const vpcAZCount = this.getContextForType('vpcAZCount', 'number', defaultValues, contextJSON)
        const openAccessPolicyEnabled = this.getContextForType('openAccessPolicyEnabled', 'boolean', defaultValues, contextJSON)
        const accessPolicyJson = this.getContextForType('accessPolicies', 'object', defaultValues, contextJSON)
        const migrationAssistanceEnabled = this.getContextForType('migrationAssistanceEnabled', 'boolean', defaultValues, contextJSON)
        const mskARN = this.getContextForType('mskARN', 'string', defaultValues, contextJSON)
        const mskEnablePublicEndpoints = this.getContextForType('mskEnablePublicEndpoints', 'boolean', defaultValues, contextJSON)
        const mskRestrictPublicAccessTo = this.getContextForType('mskRestrictPublicAccessTo', 'string', defaultValues, contextJSON)
        const mskRestrictPublicAccessType = this.getContextForType('mskRestrictPublicAccessType', 'string', defaultValues, contextJSON)
        const mskBrokerNodeCount = this.getContextForType('mskBrokerNodeCount', 'number', defaultValues, contextJSON)
        const mskSubnetIds = this.getContextForType('mskSubnetIds', 'object', defaultValues, contextJSON)
        const mskAZCount = this.getContextForType('mskAZCount', 'number', defaultValues, contextJSON)
        const replayerOutputEFSRemovalPolicy = this.getContextForType('replayerOutputEFSRemovalPolicy', 'string', defaultValues, contextJSON)
        const artifactBucketRemovalPolicy = this.getContextForType('artifactBucketRemovalPolicy', 'string', defaultValues, contextJSON)
        const addOnMigrationDeployId = this.getContextForType('addOnMigrationDeployId', 'string', defaultValues, contextJSON)
        const defaultFargateCpuArch = this.getContextForType('defaultFargateCpuArch', 'string', defaultValues, contextJSON)
        const captureProxyESServiceEnabled = this.getContextForType('captureProxyESServiceEnabled', 'boolean', defaultValues, contextJSON)
        const captureProxyESExtraArgs = this.getContextForType('captureProxyESExtraArgs', 'string', defaultValues, contextJSON)
        const migrationConsoleServiceEnabled = this.getContextForType('migrationConsoleServiceEnabled', 'boolean', defaultValues, contextJSON)
        const migrationConsoleEnableOSI = this.getContextForType('migrationConsoleEnableOSI', 'boolean', defaultValues, contextJSON)
        const migrationAPIEnabled = this.getContextForType('migrationAPIEnabled', 'boolean', defaultValues, contextJSON)
        const trafficReplayerServiceEnabled = this.getContextForType('trafficReplayerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const trafficReplayerEnableClusterFGACAuth = this.getContextForType('trafficReplayerEnableClusterFGACAuth', 'boolean', defaultValues, contextJSON)
        const trafficReplayerMaxUptime = this.getContextForType('trafficReplayerMaxUptime', 'string', defaultValues, contextJSON);
        const trafficReplayerGroupId = this.getContextForType('trafficReplayerGroupId', 'string', defaultValues, contextJSON)
        const trafficReplayerUserAgentSuffix = this.getContextForType('trafficReplayerUserAgentSuffix', 'string', defaultValues, contextJSON)
        const trafficReplayerExtraArgs = this.getContextForType('trafficReplayerExtraArgs', 'string', defaultValues, contextJSON)
        const captureProxyServiceEnabled = this.getContextForType('captureProxyServiceEnabled', 'boolean', defaultValues, contextJSON)
        const captureProxySourceEndpoint = this.getContextForType('captureProxySourceEndpoint', 'string', defaultValues, contextJSON)
        const captureProxyExtraArgs = this.getContextForType('captureProxyExtraArgs', 'string', defaultValues, contextJSON)
        const elasticsearchServiceEnabled = this.getContextForType('elasticsearchServiceEnabled', 'boolean', defaultValues, contextJSON)
        const kafkaBrokerServiceEnabled = this.getContextForType('kafkaBrokerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const targetClusterEndpoint = this.getContextForType('targetClusterEndpoint', 'string', defaultValues, contextJSON)
        const fetchMigrationEnabled = this.getContextForType('fetchMigrationEnabled', 'boolean', defaultValues, contextJSON)
        const dpPipelineTemplatePath = this.getContextForType('dpPipelineTemplatePath', 'string', defaultValues, contextJSON)
        const sourceClusterEndpoint = this.getContextForType('sourceClusterEndpoint', 'string', defaultValues, contextJSON)
        const osContainerServiceEnabled = this.getContextForType('osContainerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const otelCollectorEnabled = this.getContextForType('otelCollectorEnabled', 'boolean', defaultValues, contextJSON)
        const reindexFromSnapshotServiceEnabled = this.getContextForType('reindexFromSnapshotServiceEnabled', 'boolean', defaultValues, contextJSON)
        const reindexFromSnapshotExtraArgs = this.getContextForType('reindexFromSnapshotExtraArgs', 'string', defaultValues, contextJSON)

        const requiredFields: { [key: string]: any; } = {"stage":stage, "domainName":domainName}
        for (let key in requiredFields) {
            if (!requiredFields[key]) {
                throw new Error(`Required CDK context field ${key} is not present`)
            }
        }
        if (addOnMigrationDeployId && vpcId) {
            console.warn("Addon deployments will use the original deployment 'vpcId' regardless of passed 'vpcId' values")
        }
        let targetEndpoint
        if (targetClusterEndpoint && osContainerServiceEnabled) {
            throw new Error("The following options are mutually exclusive as only one target cluster can be specified for a given deployment: [targetClusterEndpoint, osContainerServiceEnabled]")
        } else if (targetClusterEndpoint || osContainerServiceEnabled) {
            targetEndpoint = targetClusterEndpoint ? targetClusterEndpoint : "https://opensearch:9200"
        }

        const fargateCpuArch = validateFargateCpuArch(defaultFargateCpuArch)
        const streamingSourceType = determineStreamingSourceType(kafkaBrokerServiceEnabled)

        const engineVersion = this.getContextForType('engineVersion', 'string', defaultValues, contextJSON)
        version = this.getEngineVersion(engineVersion)

        const tlsSecurityPolicyName = this.getContextForType('tlsSecurityPolicy', 'string', defaultValues, contextJSON)
        const tlsSecurityPolicy: TLSSecurityPolicy|undefined = tlsSecurityPolicyName ? TLSSecurityPolicy[tlsSecurityPolicyName as keyof typeof TLSSecurityPolicy] : undefined
        if (tlsSecurityPolicyName && !tlsSecurityPolicy) {
            throw new Error("Provided tlsSecurityPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.TLSSecurityPolicy.html")
        }

        const domainRemovalPolicyName = this.getContextForType('domainRemovalPolicy', 'string', defaultValues, contextJSON)
        const domainRemovalPolicy = parseRemovalPolicy("domainRemovalPolicy", domainRemovalPolicyName)

        let trafficReplayerCustomUserAgent
        if (props.customReplayerUserAgent && trafficReplayerUserAgentSuffix) {
            trafficReplayerCustomUserAgent = `${props.customReplayerUserAgent};${trafficReplayerUserAgentSuffix}`
        }
        else {
            trafficReplayerCustomUserAgent = trafficReplayerUserAgentSuffix ? trafficReplayerUserAgentSuffix : props.customReplayerUserAgent
        }

        const deployId = addOnMigrationDeployId ? addOnMigrationDeployId : defaultDeployId

        // If enabled re-use existing VPC and/or associated resources or create new
        let networkStack: NetworkStack|undefined
        if (vpcEnabled || addOnMigrationDeployId) {
            networkStack = new NetworkStack(scope, `networkStack-${deployId}`, {
                vpcId: vpcId,
                vpcAZCount: vpcAZCount,
                targetClusterEndpoint: targetEndpoint,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-NetworkInfra`,
                description: "This stack contains resources to create/manage networking for an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                addOnMigrationDeployId: addOnMigrationDeployId,
                env: props.env
            })
            this.stacks.push(networkStack)
        }
        let servicesYaml = new ServicesYaml();

        // There is an assumption here that for any deployment we will always have a target cluster, whether that be a
        // created Domain like below or an imported one
        let openSearchStack
        if (!targetEndpoint) {
            openSearchStack = new OpenSearchDomainStack(scope, `openSearchDomainStack-${deployId}`, {
                version: version,
                domainName: domainName,
                dataNodeInstanceType: dataNodeType,
                dataNodes: dataNodeCount,
                dedicatedManagerNodeType: dedicatedManagerNodeType,
                dedicatedManagerNodeCount: dedicatedManagerNodeCount,
                warmInstanceType: warmNodeType,
                warmNodes: warmNodeCount,
                accessPolicyJson: accessPolicyJson,
                openAccessPolicyEnabled: openAccessPolicyEnabled,
                useUnsignedBasicAuth: useUnsignedBasicAuth,
                fineGrainedManagerUserARN: fineGrainedManagerUserARN,
                fineGrainedManagerUserName: fineGrainedManagerUserName,
                fineGrainedManagerUserSecretManagerKeyARN: fineGrainedManagerUserSecretManagerKeyARN,
                enableDemoAdmin: enableDemoAdmin,
                enforceHTTPS: enforceHTTPS,
                tlsSecurityPolicy: tlsSecurityPolicy,
                ebsEnabled: ebsEnabled,
                ebsIops: ebsIops,
                ebsVolumeSize: ebsVolumeSize,
                ebsVolumeTypeName: ebsVolumeTypeName,
                encryptionAtRestEnabled: encryptionAtRestEnabled,
                encryptionAtRestKmsKeyARN: encryptionAtRestKmsKeyARN,
                appLogEnabled: loggingAppLogEnabled,
                appLogGroup: loggingAppLogGroupARN,
                nodeToNodeEncryptionEnabled: noneToNodeEncryptionEnabled,
                vpc: networkStack ? networkStack.vpc : undefined,
                vpcSubnetIds: vpcSubnetIds,
                vpcSecurityGroupIds: vpcSecurityGroupIds,
                domainAZCount: domainAZCount,
                domainRemovalPolicy: domainRemovalPolicy,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-OpenSearchDomain`,
                description: "This stack contains resources to create/manage an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                addOnMigrationDeployId: addOnMigrationDeployId,
                env: props.env
            });
            this.addDependentStacks(openSearchStack, [networkStack])
            this.stacks.push(openSearchStack)
            servicesYaml.target_cluster = openSearchStack.targetClusterYaml;
        } else {
            servicesYaml.target_cluster = { endpoint: targetEndpoint, no_auth: '' }
        }

        // Currently, placing a requirement on a VPC for a migration stack but this can be revisited
        let migrationStack
        let mskUtilityStack
        if (migrationAssistanceEnabled && networkStack && !addOnMigrationDeployId) {
            migrationStack = new MigrationAssistanceStack(scope, "migrationInfraStack", {
                vpc: networkStack.vpc,
                streamingSourceType: streamingSourceType,
                mskImportARN: mskARN,
                mskEnablePublicEndpoints: mskEnablePublicEndpoints,
                mskRestrictPublicAccessTo: mskRestrictPublicAccessTo,
                mskRestrictPublicAccessType: mskRestrictPublicAccessType,
                mskBrokerNodeCount: mskBrokerNodeCount,
                mskSubnetIds: mskSubnetIds,
                mskAZCount: mskAZCount,
                replayerOutputEFSRemovalPolicy: replayerOutputEFSRemovalPolicy,
                artifactBucketRemovalPolicy: artifactBucketRemovalPolicy,
                stackName: `OSMigrations-${stage}-${region}-MigrationInfra`,
                description: "This stack contains resources to assist migrating an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                env: props.env
            })
            this.addDependentStacks(migrationStack, [networkStack])
            this.stacks.push(migrationStack)

            if (streamingSourceType === StreamingSourceType.AWS_MSK) {
                mskUtilityStack = new MSKUtilityStack(scope, 'mskUtilityStack', {
                    vpc: networkStack.vpc,
                    mskEnablePublicEndpoints: mskEnablePublicEndpoints,
                    stackName: `OSMigrations-${stage}-${region}-MSKUtility`,
                    description: "This stack contains custom resources to add additional functionality to the MSK L1 construct",
                    stage: stage,
                    defaultDeployId: defaultDeployId,
                    env: props.env
                })
                this.addDependentStacks(mskUtilityStack, [migrationStack])
                this.stacks.push(mskUtilityStack)
            }
        }

        let osContainerStack
        if (osContainerServiceEnabled && networkStack && migrationStack) {
            osContainerStack = new OpenSearchContainerStack(scope, `opensearch-container-${deployId}`, {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-OpenSearchContainer`,
                description: "This stack contains resources for the OpenSearch Container ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                addOnMigrationDeployId: addOnMigrationDeployId,
                enableDemoAdmin: true,
                env: props.env
            })
            this.addDependentStacks(osContainerStack, [migrationStack])
            this.stacks.push(osContainerStack)
        }

        let kafkaBrokerStack
        if (kafkaBrokerServiceEnabled && networkStack && migrationStack) {
            kafkaBrokerStack = new KafkaStack(scope, "kafka", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-KafkaBroker`,
                description: "This stack contains resources for the Kafka Broker ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(kafkaBrokerStack, [migrationStack])
            this.stacks.push(kafkaBrokerStack)
        }

        let fetchMigrationStack
        if (fetchMigrationEnabled && networkStack && migrationStack) {
            fetchMigrationStack = new FetchMigrationStack(scope, "fetchMigrationStack", {
                vpc: networkStack.vpc,
                dpPipelineTemplatePath: dpPipelineTemplatePath,
                sourceEndpoint: sourceClusterEndpoint,
                stackName: `OSMigrations-${stage}-${region}-FetchMigration`,
                description: "This stack contains resources to assist migrating historical data to an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(fetchMigrationStack, [migrationStack, openSearchStack, osContainerStack])
            this.stacks.push(fetchMigrationStack)
        }

        let reindexFromSnapshotStack
        if (reindexFromSnapshotServiceEnabled && networkStack && migrationStack) {
            reindexFromSnapshotStack = new ReindexFromSnapshotStack(scope, "reindexFromSnapshotStack", {
                vpc: networkStack.vpc,
                sourceEndpoint: sourceClusterEndpoint,
                extraArgs: reindexFromSnapshotExtraArgs,
                stackName: `OSMigrations-${stage}-${region}-ReindexFromSnapshot`,
                description: "This stack contains resources to assist migrating historical data, via Reindex from Snapshot, to a target cluster",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(reindexFromSnapshotStack, [migrationStack, openSearchStack, osContainerStack])
            this.stacks.push(reindexFromSnapshotStack)
            servicesYaml.backfill = reindexFromSnapshotStack.rfsBackfillYaml;

        }

        let captureProxyESStack
        if (captureProxyESServiceEnabled && networkStack && migrationStack) {
            captureProxyESStack = new CaptureProxyESStack(scope, "capture-proxy-es", {
                vpc: networkStack.vpc,
                otelCollectorEnabled: otelCollectorEnabled,
                streamingSourceType: streamingSourceType,
                extraArgs: captureProxyESExtraArgs,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxyES`,
                description: "This stack contains resources for the Capture Proxy/Elasticsearch ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(captureProxyESStack, [migrationStack, mskUtilityStack, kafkaBrokerStack])
            this.stacks.push(captureProxyESStack)
        }

        let trafficReplayerStack
        if ((trafficReplayerServiceEnabled && networkStack && migrationStack) || (addOnMigrationDeployId && networkStack)) {
            trafficReplayerStack = new TrafficReplayerStack(scope, `traffic-replayer-${deployId}`, {
                vpc: networkStack.vpc,
                enableClusterFGACAuth: trafficReplayerEnableClusterFGACAuth,
                addOnMigrationDeployId: addOnMigrationDeployId,
                customKafkaGroupId: trafficReplayerGroupId,
                userAgentSuffix: trafficReplayerCustomUserAgent,
                extraArgs: trafficReplayerExtraArgs,
                otelCollectorEnabled: otelCollectorEnabled,
                streamingSourceType: streamingSourceType,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-TrafficReplayer`,
                description: "This stack contains resources for the Traffic Replayer ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                maxUptime: trafficReplayerMaxUptime ? Duration.parse(trafficReplayerMaxUptime) : undefined,
                env: props.env
            })
            this.addDependentStacks(trafficReplayerStack, [networkStack, migrationStack, mskUtilityStack,
                kafkaBrokerStack, openSearchStack, osContainerStack])
            this.stacks.push(trafficReplayerStack)
        }

        let elasticsearchStack
        if (elasticsearchServiceEnabled && networkStack && migrationStack) {
            elasticsearchStack = new ElasticsearchStack(scope, "elasticsearch", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-Elasticsearch`,
                description: "This stack contains resources for a testing mock Elasticsearch single node cluster ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(elasticsearchStack, [migrationStack])
            this.stacks.push(elasticsearchStack)
        }

        let captureProxyStack
        if (captureProxyServiceEnabled && networkStack && migrationStack) {
            captureProxyStack = new CaptureProxyStack(scope, "capture-proxy", {
                vpc: networkStack.vpc,
                customSourceClusterEndpoint: captureProxySourceEndpoint,
                otelCollectorEnabled: otelCollectorEnabled,
                streamingSourceType: streamingSourceType,
                extraArgs: captureProxyExtraArgs,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxy`,
                description: "This stack contains resources for the Capture Proxy ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(captureProxyStack, [elasticsearchStack, migrationStack,
                kafkaBrokerStack, mskUtilityStack])
            this.stacks.push(captureProxyStack)
        }

        let migrationConsoleStack
        if (migrationConsoleServiceEnabled && networkStack && migrationStack) {
            migrationConsoleStack = new MigrationConsoleStack(scope, "migration-console", {
                migrationsSolutionVersion: props.migrationsSolutionVersion,
                vpc: networkStack.vpc,
                streamingSourceType: streamingSourceType,
                fetchMigrationEnabled: fetchMigrationEnabled,
                migrationConsoleEnableOSI: migrationConsoleEnableOSI,
                migrationAPIEnabled: migrationAPIEnabled,
                servicesYaml: servicesYaml,
                // The default value is correct if we deploy the capture proxy (e.g. captureProxyServiceEnabled or captureProxyESServiceEnabled),
                // but not if the user does it on their own (in which case we use captureProxySourceEndpoint)
                sourceClusterEndpoint: !(captureProxyServiceEnabled || captureProxyESServiceEnabled) ? captureProxySourceEndpoint : undefined,
                stackName: `OSMigrations-${stage}-${region}-MigrationConsole`,
                description: "This stack contains resources for the Migration Console ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            // To enable the Migration Console to make requests to other service endpoints with services,
            // it must be deployed after any connected services
            this.addDependentStacks(migrationConsoleStack, [captureProxyESStack, captureProxyStack, elasticsearchStack,
                fetchMigrationStack, openSearchStack, osContainerStack, migrationStack, kafkaBrokerStack, mskUtilityStack])
            this.stacks.push(migrationConsoleStack)
        }

        if (props.migrationsAppRegistryARN) {
            this.addStacksToAppRegistry(props.migrationsAppRegistryARN, this.stacks)
        }
    }
}