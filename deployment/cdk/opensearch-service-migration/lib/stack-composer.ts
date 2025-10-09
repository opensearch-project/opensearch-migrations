import {Construct} from "constructs";
import {Duration, Stack, StackProps} from "aws-cdk-lib";
import {readFileSync} from 'node:fs';
import {OpenSearchDomainStack} from "./opensearch-domain-stack";
import {EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import * as defaultValuesJson from "../default-values.json"
import {NetworkStack} from "./network-stack";
import {MigrationAssistanceStack} from "./migration-assistance-stack";
import {MigrationConsoleStack} from "./service-stacks/migration-console-stack";
import {TrafficReplayerStack} from "./service-stacks/traffic-replayer-stack";
import {CaptureProxyStack} from "./service-stacks/capture-proxy-stack";
import {ElasticsearchStack} from "./service-stacks/elasticsearch-stack";
import {KafkaStack} from "./service-stacks/kafka-stack";
import {Application} from "@aws-cdk/aws-servicecatalogappregistry-alpha";
import {determineStreamingSourceType, StreamingSourceType} from "./streaming-source-type";
import {
    MAX_STAGE_NAME_LENGTH,
    MigrationSSMParameter,
    parseRemovalPolicy,
    parseSnapshotDefinition,
    validateFargateCpuArch
} from "./common-utilities";
import {ReindexFromSnapshotStack} from "./service-stacks/reindex-from-snapshot-stack";
import {ClientOptions, ServicesYaml, SnapshotYaml} from "./migration-services-yaml";
import {CdkLogger} from "./cdk-logger";

export interface StackPropsExt extends StackProps {
    readonly stage: string,
    readonly defaultDeployId: string,
    readonly addOnMigrationDeployId?: string
}

export interface StackComposerProps extends StackProps {
    readonly migrationsSolutionVersion: string,
    readonly migrationsAppRegistryARN?: string,
    readonly migrationsUserAgent?: string
}

export class StackComposer {
    public stacks: Stack[] = [];

    private addStacksToAppRegistry(appRegistryAppARN: string, allStacks: Stack[]) {
        for (const stack of allStacks) {
            const appRegistryApp = Application.fromApplicationArn(stack, 'AppRegistryApplicationImport', appRegistryAppARN)
            appRegistryApp.associateApplicationWithStack(stack)
        }
    }

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    private getContextForType(optionName: string, expectedType: string, defaultValues: Record<string, any>, contextJSON: Record<string, any>): any {
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
                return Number.parseInt(option)
            }
            if (expectedType === 'boolean' || expectedType === 'object') {
                try {
                    return JSON.parse(option)
                } catch (e) {
                    if (e instanceof SyntaxError) {
                        CdkLogger.error(`Unable to parse option: ${optionName} with expected type: ${expectedType}`)
                    }
                    throw e
                }
            }
        }
        // Values provided by the cdk.context.json should be of the desired type
        if (typeof option !== expectedType) {
            throw new TypeError(`Type provided by cdk.context.json for ${optionName} was ${typeof option} but expected ${expectedType}`)
        }
        return option
    }

    private getEngineVersion(engineVersionString: string) : EngineVersion {
        let version: EngineVersion
        if (engineVersionString?.startsWith("OS_")) {
            // Will accept a period delimited version string (i.e. 1.3) and return a proper EngineVersion
            version = EngineVersion.openSearch(engineVersionString.substring(3))
        } else if (engineVersionString?.startsWith("ES_")) {
            version = EngineVersion.elasticsearch(engineVersionString.substring(3))
        } else {
            throw new Error(`Engine version (${engineVersionString}) is not present or does not match the expected format, i.e. OS_1.3 or ES_7.9`)
        }
        return version
    }

    private addDependentStacks(primaryStack: Stack, dependantStacks: (Stack|undefined)[]) {
        for (const stack of dependantStacks) {
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

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const defaultValues: Record<string, any> = defaultValuesJson
        if (!props.env?.region) {
            throw new Error('Missing at least one of required fields [region] in props.env. ' +
                'Has AWS been configured for this environment?');
        }
        const region = props.env.region
        const defaultDeployId = 'default'

        const contextId = scope.node.tryGetContext("contextId")
        if (!contextId) {
            throw new Error("Required context field 'contextId' not provided")
        }
        const contextJSON = this.parseContextBlock(scope, contextId)
        CdkLogger.info(`Context block for '${contextId}':\n---\n${JSON.stringify(contextJSON, null, 3)}\n---`);

        const stage = this.getContextForType('stage', 'string', defaultValues, contextJSON)

        const domainName = this.getContextForType('domainName', 'string', defaultValues, contextJSON)
        const engineVersion = this.getContextForType('engineVersion', 'string', defaultValues, contextJSON)
        const dataNodeType = this.getContextForType('dataNodeType', 'string', defaultValues, contextJSON)
        const dataNodeCount = this.getContextForType('dataNodeCount', 'number', defaultValues, contextJSON)
        const dedicatedManagerNodeType = this.getContextForType('dedicatedManagerNodeType', 'string', defaultValues, contextJSON)
        const dedicatedManagerNodeCount = this.getContextForType('dedicatedManagerNodeCount', 'number', defaultValues, contextJSON)
        const warmNodeType = this.getContextForType('warmNodeType', 'string', defaultValues, contextJSON)
        const warmNodeCount = this.getContextForType('warmNodeCount', 'number', defaultValues, contextJSON)
        const useUnsignedBasicAuth = this.getContextForType('useUnsignedBasicAuth', 'boolean', defaultValues, contextJSON)
        const fineGrainedManagerUserARN = this.getContextForType('fineGrainedManagerUserARN', 'string', defaultValues, contextJSON)
        const fineGrainedManagerUserSecretARN = this.getContextForType('fineGrainedManagerUserSecretARN', 'string', defaultValues, contextJSON)
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
        const mskBrokersPerAZCount = this.getContextForType('mskBrokersPerAZCount', 'number', defaultValues, contextJSON)
        const replayerOutputEFSRemovalPolicy = this.getContextForType('replayerOutputEFSRemovalPolicy', 'string', defaultValues, contextJSON)
        const artifactBucketRemovalPolicy = this.getContextForType('artifactBucketRemovalPolicy', 'string', defaultValues, contextJSON)
        const addOnMigrationDeployId = this.getContextForType('addOnMigrationDeployId', 'string', defaultValues, contextJSON)
        const defaultFargateCpuArch = this.getContextForType('defaultFargateCpuArch', 'string', defaultValues, contextJSON)
        const migrationConsoleServiceEnabled = this.getContextForType('migrationConsoleServiceEnabled', 'boolean', defaultValues, contextJSON)
        const trafficReplayerServiceEnabled = this.getContextForType('trafficReplayerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const trafficReplayerMaxUptime = this.getContextForType('trafficReplayerMaxUptime', 'string', defaultValues, contextJSON);
        const trafficReplayerGroupId = this.getContextForType('trafficReplayerGroupId', 'string', defaultValues, contextJSON)
        const trafficReplayerUserAgentSuffix = this.getContextForType('trafficReplayerUserAgentSuffix', 'string', defaultValues, contextJSON)
        const trafficReplayerExtraArgs = this.getContextForType('trafficReplayerExtraArgs', 'string', defaultValues, contextJSON)
        const captureProxyServiceEnabled = this.getContextForType('captureProxyServiceEnabled', 'boolean', defaultValues, contextJSON)
        const captureProxyDesiredCount = this.getContextForType('captureProxyDesiredCount', 'number', defaultValues, contextJSON)
        const targetClusterProxyServiceEnabled = this.getContextForType('targetClusterProxyServiceEnabled', 'boolean', defaultValues, contextJSON)
        const targetClusterProxyDesiredCount = this.getContextForType('targetClusterProxyDesiredCount', 'number', defaultValues, contextJSON)
        const captureProxyExtraArgs = this.getContextForType('captureProxyExtraArgs', 'string', defaultValues, contextJSON)
        const elasticsearchServiceEnabled = this.getContextForType('elasticsearchServiceEnabled', 'boolean', defaultValues, contextJSON)
        const kafkaBrokerServiceEnabled = this.getContextForType('kafkaBrokerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const otelCollectorEnabled = this.getContextForType('otelCollectorEnabled', 'boolean', defaultValues, contextJSON)
        const reindexFromSnapshotServiceEnabled = this.getContextForType('reindexFromSnapshotServiceEnabled', 'boolean', defaultValues, contextJSON)
        const reindexFromSnapshotExtraArgs = this.getContextForType('reindexFromSnapshotExtraArgs', 'string', defaultValues, contextJSON)
        const reindexFromSnapshotMaxShardSizeGiB = this.getContextForType('reindexFromSnapshotMaxShardSizeGiB', 'number', defaultValues, contextJSON)
        const reindexFromSnapshotWorkerSize = this.getContextForType('reindexFromSnapshotWorkerSize', 'string', defaultValues, contextJSON)
        const albAcmCertArn = this.getContextForType('albAcmCertArn', 'string', defaultValues, contextJSON);
        let managedServiceSourceSnapshotEnabled = this.getContextForType('managedServiceSourceSnapshotEnabled', 'boolean', defaultValues, contextJSON)

        const deployId = addOnMigrationDeployId ?? defaultDeployId
        // We're in a transition state from an older model with limited, individually defined fields and heading towards objects
        // that fully define the source and target cluster configurations. For the time being, we're supporting both.
        const sourceClusterDisabledField = this.getContextForType('sourceClusterDisabled', 'boolean', defaultValues, contextJSON)
        const sourceClusterEndpointField = this.getContextForType('sourceClusterEndpoint', 'string', defaultValues, contextJSON)
        const sourceClusterField = this.getContextForType('sourceCluster', 'object', defaultValues, contextJSON)
        let sourceClusterDefinition = sourceClusterField

        if (!sourceClusterField && sourceClusterEndpointField) {
            CdkLogger.warn("The `sourceClusterEndpoint` option is being deprecated in favor of a `endpoint` field in the `sourceCluster` object.")
            CdkLogger.warn("Please update your CDK context block to use the `sourceCluster` object.")
            CdkLogger.warn("Defaulting to source cluster version: ES_7.10")
            sourceClusterDefinition = {
                "endpoint": sourceClusterEndpointField,
                "auth": {"type": "none"},
                "version": "ES_7.10"
            }
        }
        const sourceClusterDisabled = (sourceClusterField?.disabled ?? sourceClusterDisabledField)
        if (sourceClusterDisabled) {
            if (sourceClusterDisabledField) {
                CdkLogger.warn("The `sourceClusterDisabled` field is being deprecated in favor of a `disabled: true` field in the `sourceCluster` object.")
            }
            sourceClusterDefinition = undefined
        }

        const targetClusterEndpointField = this.getContextForType('targetClusterEndpoint', 'string', defaultValues, contextJSON)
        let targetClusterDefinition = this.getContextForType('targetCluster', 'object', defaultValues, contextJSON)
        const usePreexistingTargetCluster = !!(targetClusterEndpointField ?? targetClusterDefinition)
        if (!targetClusterDefinition && usePreexistingTargetCluster) {
            CdkLogger.warn("`targetClusterEndpoint` is being deprecated in favor of a `targetCluster` object.")
            CdkLogger.warn("Please update your CDK context block to use the `targetCluster` object.")
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            let auth: any = {"type": "none"}
            if (fineGrainedManagerUserSecretARN) {
                CdkLogger.warn(`Use of ${fineGrainedManagerUserSecretARN} with a preexisting target cluster
                    will be deprecated in favor of using a \`targetCluster\` object. Please update your CDK context block.`)
                auth = {
                    "type": "basic",
                    "userSecretArn": fineGrainedManagerUserSecretARN
                }
            }
            targetClusterDefinition = {"endpoint": targetClusterEndpointField, "auth": auth}
        }

        // Ensure that target cluster username and password are not defined in multiple places
        if (targetClusterDefinition && fineGrainedManagerUserSecretARN) {
            throw new Error("The `fineGrainedManagerUserSecretARN` option can only be used when a domain is being " +
                "provisioned by this tooling, which is contraindicated by `targetCluster` being provided.")
        }

        // Ensure that target version is not defined in multiple places, but `engineVersion` is set as a default value, so this is
        // a warning instead of an error.
        if (usePreexistingTargetCluster && engineVersion) {
            CdkLogger.warn("The `engineVersion` value will be ignored because it's only used when a domain is being provisioned by this tooling" +
                " and in this case, `targetCluster` was provided to define an existing target cluster."
            )
        }

        const engineVersionValue = engineVersion ? this.getEngineVersion(engineVersion) : this.getEngineVersion('OS_2.15')

        if (reindexFromSnapshotWorkerSize !== "default" && reindexFromSnapshotWorkerSize !== "maximum") {
            throw new Error("Invalid value for reindexFromSnapshotWorkerSize, must be either 'default' or 'maximum'")
        }

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const requiredFields: Record<string, any> = {"stage":stage}
        for (const key in requiredFields) {
            if (!requiredFields[key]) {
                throw new Error(`Required CDK context field ${key} is not present`)
            }
        }
        if (addOnMigrationDeployId && vpcId) {
            CdkLogger.warn("Add-on deployments will use the original deployment 'vpcId' regardless of passed 'vpcId' values")
        }
        if (stage.length > MAX_STAGE_NAME_LENGTH) {
            throw new Error(`Maximum allowed stage name length is ${MAX_STAGE_NAME_LENGTH} characters but received ${stage}`)
        }
        const clusterDomainName = domainName ?? `os-cluster-${stage}`

        const fargateCpuArch = validateFargateCpuArch(defaultFargateCpuArch)

        const streamingSourceType = determineStreamingSourceType(
            captureProxyServiceEnabled,
            trafficReplayerServiceEnabled,
            kafkaBrokerServiceEnabled
        );

        const tlsSecurityPolicyName = this.getContextForType('tlsSecurityPolicy', 'string', defaultValues, contextJSON)
        const tlsSecurityPolicy: TLSSecurityPolicy|undefined = tlsSecurityPolicyName ? TLSSecurityPolicy[tlsSecurityPolicyName as keyof typeof TLSSecurityPolicy] : undefined
        if (tlsSecurityPolicyName && !tlsSecurityPolicy) {
            throw new Error("Provided tlsSecurityPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.TLSSecurityPolicy.html")
        }

        const domainRemovalPolicyName = this.getContextForType('domainRemovalPolicy', 'string', defaultValues, contextJSON)
        const domainRemovalPolicy = parseRemovalPolicy("domainRemovalPolicy", domainRemovalPolicyName)

        let trafficReplayerCustomUserAgent
        if (props.migrationsUserAgent && trafficReplayerUserAgentSuffix) {
            trafficReplayerCustomUserAgent = `${props.migrationsUserAgent};${trafficReplayerUserAgentSuffix}`
        }
        else {
            trafficReplayerCustomUserAgent = trafficReplayerUserAgentSuffix ?? props.migrationsUserAgent
        }

        if (!sourceClusterDisabled && (!sourceClusterDefinition && !elasticsearchServiceEnabled && !captureProxyServiceEnabled)) {
            throw new Error("A source cluster must be specified by one of: [sourceCluster, elasticsearchServiceEnabled, captureProxyServiceEnabled] or" +
                            " disabled with a definition similar to \"sourceCluster\":{\"disabled\":true} ");
        }

        // If enabled re-use existing VPC and/or associated resources or create new
        let networkStack: NetworkStack|undefined
        if (vpcEnabled || addOnMigrationDeployId) {
            networkStack = new NetworkStack(scope, `networkStack-${deployId}`, {
                vpcId: vpcId,
                vpcSubnetIds: vpcSubnetIds,
                vpcAZCount: vpcAZCount,
                streamingSourceType: streamingSourceType,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-NetworkInfra`,
                description: "This stack contains resources to create/manage networking for an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                addOnMigrationDeployId: addOnMigrationDeployId,
                albAcmCertArn: albAcmCertArn,
                elasticsearchServiceEnabled,
                captureProxyServiceEnabled,
                targetClusterProxyServiceEnabled,
                sourceClusterDisabled,
                sourceClusterDefinition,
                targetClusterDefinition,
                managedServiceSourceSnapshotEnabled,
                env: props.env,
            })
            this.stacks.push(networkStack)
        }

        const servicesYaml = new ServicesYaml()
        servicesYaml.source_cluster = networkStack?.sourceClusterYaml
        if (networkStack?.targetClusterYaml) {
            servicesYaml.target_cluster = networkStack.targetClusterYaml
        }
        if (props.migrationsUserAgent) {
            servicesYaml.client_options = new ClientOptions()
            servicesYaml.client_options.user_agent_extra = props.migrationsUserAgent
        }
        // Resolve source cluster version even for disabled source clusters
        const resolvedSourceClusterVersion = servicesYaml.source_cluster?.version ?? sourceClusterField?.version
        const existingSnapshotDefinition = this.getContextForType('snapshot', 'object', defaultValues, contextJSON)
        let snapshotYaml
        if (existingSnapshotDefinition) {
            if(!sourceClusterField?.version) {
                throw new Error("The `sourceCluster` object must be provided with a `version` field when using an external snapshot to ensure proper parsing of " +
                    "the snapshot based on cluster version. The `sourceCluster` object can still be disabled by providing the `disabled: true` field which would " +
                    "would result in a minimal source cluster object similar to: \"sourceCluster\":{\"version\":\"ES 7.10\",\"disabled\":true}")
            }
            snapshotYaml = parseSnapshotDefinition(existingSnapshotDefinition)
        } else {
            snapshotYaml = new SnapshotYaml();
            snapshotYaml.snapshot_name = "rfs-snapshot"
            snapshotYaml.snapshot_repo_name = "migration_assistant_repo"
        }
        servicesYaml.snapshot = snapshotYaml

        if (servicesYaml.source_cluster?.auth.sigv4 && managedServiceSourceSnapshotEnabled == null) {
            managedServiceSourceSnapshotEnabled = true;
            CdkLogger.info("`managedServiceSourceSnapshotEnabled` is not set with source cluster set with sigv4 auth, defaulting to true.")
        }

        let openSearchStack
        if (!targetClusterDefinition) {
            openSearchStack = new OpenSearchDomainStack(scope, `openSearchDomainStack-${deployId}`, {
                version: engineVersionValue,
                domainName: clusterDomainName,
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
                fineGrainedManagerUserSecretARN: fineGrainedManagerUserSecretARN,
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
                vpcDetails: networkStack ? networkStack.vpcDetails : undefined,
                vpcSecurityGroupIds: vpcSecurityGroupIds,
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
        }

        let migrationStack
        if (migrationAssistanceEnabled && networkStack && !addOnMigrationDeployId) {
            migrationStack = new MigrationAssistanceStack(scope, "migrationInfraStack", {
                vpcDetails: networkStack.vpcDetails,
                streamingSourceType: streamingSourceType,
                mskImportARN: mskARN,
                mskBrokersPerAZCount: mskBrokersPerAZCount,
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
            servicesYaml.kafka = migrationStack.kafkaYaml;
            if (!existingSnapshotDefinition) {
                snapshotYaml.s3 = {
                    repo_uri: `s3://${migrationStack.artifactBucketName}/rfs-snapshot-repo`,
                    aws_region: region
                };
            }
        }

        let kafkaBrokerStack
        if (kafkaBrokerServiceEnabled && networkStack && migrationStack) {
            kafkaBrokerStack = new KafkaStack(scope, "kafka", {
                vpcDetails: networkStack.vpcDetails,
                stackName: `OSMigrations-${stage}-${region}-KafkaBroker`,
                description: "This stack contains resources for the Kafka Broker ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env
            })
            this.addDependentStacks(kafkaBrokerStack, [migrationStack])
            this.stacks.push(kafkaBrokerStack)
            servicesYaml.kafka = kafkaBrokerStack.kafkaYaml;
        }

        let reindexFromSnapshotStack
        if (reindexFromSnapshotServiceEnabled && networkStack && migrationStack) {
            reindexFromSnapshotStack = new ReindexFromSnapshotStack(scope, "reindexFromSnapshotStack", {
                vpcDetails: networkStack.vpcDetails,
                extraArgs: reindexFromSnapshotExtraArgs,
                clusterAuthDetails: servicesYaml.target_cluster?.auth,
                skipClusterCertCheck: servicesYaml.target_cluster?.allowInsecure,
                sourceClusterVersion: resolvedSourceClusterVersion,
                stackName: `OSMigrations-${stage}-${region}-ReindexFromSnapshot`,
                description: "This stack contains resources to assist migrating historical data, via Reindex from Snapshot, to a target cluster",
                stage: stage,
                otelCollectorEnabled: otelCollectorEnabled,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env,
                maxShardSizeGiB: reindexFromSnapshotMaxShardSizeGiB,
                reindexFromSnapshotWorkerSize,
                snapshotYaml: servicesYaml.snapshot
            })
            this.addDependentStacks(reindexFromSnapshotStack, [migrationStack, openSearchStack])
            this.stacks.push(reindexFromSnapshotStack)
            servicesYaml.backfill = reindexFromSnapshotStack.rfsBackfillYaml;
        }


        let trafficReplayerStack
        if ((trafficReplayerServiceEnabled && networkStack && migrationStack) || (addOnMigrationDeployId && networkStack)) {
            trafficReplayerStack = new TrafficReplayerStack(scope, `traffic-replayer-${deployId}`, {
                vpcDetails: networkStack.vpcDetails,
                clusterAuthDetails: servicesYaml.target_cluster.auth,
                skipClusterCertCheck: servicesYaml.target_cluster?.allowInsecure,
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
            this.addDependentStacks(trafficReplayerStack, [networkStack, migrationStack,kafkaBrokerStack,
                openSearchStack])
            this.stacks.push(trafficReplayerStack)
            servicesYaml.replayer = trafficReplayerStack.replayerYaml;
        }

        let elasticsearchStack
        if (elasticsearchServiceEnabled && networkStack && migrationStack) {
            elasticsearchStack = new ElasticsearchStack(scope, "elasticsearch", {
                vpcDetails: networkStack.vpcDetails,
                stackName: `OSMigrations-${stage}-${region}-Elasticsearch`,
                description: "This stack contains resources for a testing mock Elasticsearch single node cluster ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                targetGroups: [networkStack.albSourceClusterTG],
                env: props.env
            })
            this.addDependentStacks(elasticsearchStack, [migrationStack])
            this.stacks.push(elasticsearchStack)
        }

        let captureProxyStack
        if (captureProxyServiceEnabled && networkStack && migrationStack) {
            captureProxyStack = new CaptureProxyStack(scope, "capture-proxy", {
                vpcDetails: networkStack.vpcDetails,
                destinationConfig: {
                    endpointMigrationSSMParameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT,
                },
                otelCollectorEnabled: otelCollectorEnabled,
                skipClusterCertCheck: servicesYaml.source_cluster?.allowInsecure,
                streamingSourceType: streamingSourceType,
                extraArgs: captureProxyExtraArgs,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxy`,
                description: "This stack contains resources for the Capture Proxy ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                targetGroups: [networkStack.albSourceProxyTG],
                taskInstanceCount: captureProxyDesiredCount,
                env: props.env
            })
            this.addDependentStacks(captureProxyStack, [elasticsearchStack, migrationStack,
                kafkaBrokerStack])
            this.stacks.push(captureProxyStack)
        }

        let targetClusterProxyStack
        if (targetClusterProxyServiceEnabled && networkStack && migrationStack) {
            targetClusterProxyStack = new CaptureProxyStack(scope, "target-cluster-proxy", {
                serviceName: "target-cluster-proxy",
                vpcDetails: networkStack.vpcDetails,
                destinationConfig: {
                    endpointMigrationSSMParameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
                    securityGroupMigrationSSMParameter: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID,
                },
                otelCollectorEnabled: false,
                skipClusterCertCheck: servicesYaml.target_cluster?.allowInsecure,
                streamingSourceType: StreamingSourceType.DISABLED,
                extraArgs: "--noCapture",
                stackName: `OSMigrations-${stage}-${region}-TargetClusterProxy`,
                description: "This stack contains resources for the Target Cluster Proxy ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                targetGroups: [networkStack.albTargetProxyTG],
                taskInstanceCount: targetClusterProxyDesiredCount,
                env: props.env,
            })
            this.addDependentStacks(targetClusterProxyStack, [migrationStack])
            this.stacks.push(targetClusterProxyStack)
        }

        let migrationConsoleStack
        if (migrationConsoleServiceEnabled && networkStack && migrationStack) {
            migrationConsoleStack = new MigrationConsoleStack(scope, "migration-console", {
                migrationsSolutionVersion: props.migrationsSolutionVersion,
                vpcDetails: networkStack.vpcDetails,
                streamingSourceType: streamingSourceType,
                servicesYaml: servicesYaml,
                sourceClusterVersion: resolvedSourceClusterVersion,
                stackName: `OSMigrations-${stage}-${region}-MigrationConsole`,
                description: "This stack contains resources for the Migration Console ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                otelCollectorEnabled,
                managedServiceSourceSnapshotEnabled,
                env: props.env
            })
            // To enable the Migration Console to make requests to other service endpoints with services,
            // it must be deployed after any connected services
            this.addDependentStacks(migrationConsoleStack, [captureProxyStack, elasticsearchStack,
                openSearchStack, migrationStack, kafkaBrokerStack])
            this.stacks.push(migrationConsoleStack)
        }

        if (props.migrationsAppRegistryARN) {
            this.addStacksToAppRegistry(props.migrationsAppRegistryARN, this.stacks)
        }
    }
}
