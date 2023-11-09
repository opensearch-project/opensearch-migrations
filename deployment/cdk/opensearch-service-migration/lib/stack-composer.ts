import {Construct} from "constructs";
import {RemovalPolicy, Stack, StackProps} from "aws-cdk-lib";
import {OpenSearchDomainStack} from "./opensearch-domain-stack";
import {EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import {EbsDeviceVolumeType} from "aws-cdk-lib/aws-ec2";
import {AnyPrincipal, Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import * as defaultValuesJson from "../default-values.json"
import {NetworkStack} from "./network-stack";
import {MigrationAssistanceStack} from "./migration-assistance-stack";
import {FetchMigrationStack} from "./fetch-migration-stack";
import {MSKUtilityStack} from "./msk-utility-stack";
import {MigrationConsoleStack} from "./service-stacks/migration-console-stack";
import {CaptureProxyESStack} from "./service-stacks/capture-proxy-es-stack";
import {TrafficReplayerStack} from "./service-stacks/traffic-replayer-stack";
import {TrafficComparatorStack} from "./service-stacks/traffic-comparator-stack";
import {TrafficComparatorJupyterStack} from "./service-stacks/traffic-comparator-jupyter-stack";
import {CaptureProxyStack} from "./service-stacks/capture-proxy-stack";
import {ElasticsearchStack} from "./service-stacks/elasticsearch-stack";
import {KafkaBrokerStack} from "./service-stacks/kafka-broker-stack";
import {KafkaZookeeperStack} from "./service-stacks/kafka-zookeeper-stack";
import {Application} from "@aws-cdk/aws-servicecatalogappregistry-alpha";

export interface StackPropsExt extends StackProps {
    readonly stage: string,
    readonly defaultDeployId: string
    readonly addOnMigrationDeployId?: string
}

export interface StackComposerProps extends StackProps {
    readonly migrationsAppRegistryARN?: string
}

export class StackComposer {
    public stacks: Stack[] = [];

    private addStacksToAppRegistry(scope: Construct, appRegistryAppARN: string, allStacks: Stack[]) {
        for (let stack of allStacks) {
            const appRegistryApp = Application.fromApplicationArn(stack, 'AppRegistryApplicationImport', appRegistryAppARN)
            appRegistryApp.associateApplicationWithStack(stack)
        }
    }

    constructor(scope: Construct, props: StackComposerProps) {

        const defaultValues: { [x: string]: (any); } = defaultValuesJson
        const account = props.env?.account
        const region = props.env?.region
        const defaultDeployId = 'default'

        const contextId = scope.node.tryGetContext("contextId")
        if (!contextId) {
            throw new Error("Required context field 'contextId' not provided")
        }
        const contextJSON = scope.node.tryGetContext(contextId)
        if (!contextJSON) {
            throw new Error(`No CDK context block found for contextId '${contextId}'`)
        }
        const stage = getContextForType('stage', 'string')

        let version: EngineVersion
        let accessPolicies: PolicyStatement[]|undefined
        const domainName = getContextForType('domainName', 'string')
        const dataNodeType = getContextForType('dataNodeType', 'string')
        const dataNodeCount = getContextForType('dataNodeCount', 'number')
        const dedicatedManagerNodeType = getContextForType('dedicatedManagerNodeType', 'string')
        const dedicatedManagerNodeCount = getContextForType('dedicatedManagerNodeCount', 'number')
        const warmNodeType = getContextForType('warmNodeType', 'string')
        const warmNodeCount = getContextForType('warmNodeCount', 'number')
        const useUnsignedBasicAuth = getContextForType('useUnsignedBasicAuth', 'boolean')
        const fineGrainedManagerUserARN = getContextForType('fineGrainedManagerUserARN', 'string')
        const fineGrainedManagerUserName = getContextForType('fineGrainedManagerUserName', 'string')
        const fineGrainedManagerUserSecretManagerKeyARN = getContextForType('fineGrainedManagerUserSecretManagerKeyARN', 'string')
        const enableDemoAdmin = getContextForType('enableDemoAdmin', 'boolean')
        const enforceHTTPS = getContextForType('enforceHTTPS', 'boolean')
        const ebsEnabled = getContextForType('ebsEnabled', 'boolean')
        const ebsIops = getContextForType('ebsIops', 'number')
        const ebsVolumeSize = getContextForType('ebsVolumeSize', 'number')
        const encryptionAtRestEnabled = getContextForType('encryptionAtRestEnabled', 'boolean')
        const encryptionAtRestKmsKeyARN = getContextForType("encryptionAtRestKmsKeyARN", 'string')
        const loggingAppLogEnabled = getContextForType('loggingAppLogEnabled', 'boolean')
        const loggingAppLogGroupARN = getContextForType('loggingAppLogGroupARN', 'string')
        const noneToNodeEncryptionEnabled = getContextForType('nodeToNodeEncryptionEnabled', 'boolean')
        const vpcId = getContextForType('vpcId', 'string')
        const vpcEnabled = getContextForType('vpcEnabled', 'boolean')
        const vpcSecurityGroupIds = getContextForType('vpcSecurityGroupIds', 'object')
        const vpcSubnetIds = getContextForType('vpcSubnetIds', 'object')
        const openAccessPolicyEnabled = getContextForType('openAccessPolicyEnabled', 'boolean')
        const availabilityZoneCount = getContextForType('availabilityZoneCount', 'number')
        const migrationAssistanceEnabled = getContextForType('migrationAssistanceEnabled', 'boolean')
        const mskARN = getContextForType('mskARN', 'string')
        const mskEnablePublicEndpoints = getContextForType('mskEnablePublicEndpoints', 'boolean')
        const mskRestrictPublicAccessTo = getContextForType('mskRestrictPublicAccessTo', 'string')
        const mskRestrictPublicAccessType = getContextForType('mskRestrictPublicAccessType', 'string')
        const mskBrokerNodeCount = getContextForType('mskBrokerNodeCount', 'number')
        const addOnMigrationDeployId = getContextForType('addOnMigrationDeployId', 'string')
        const captureProxyESServiceEnabled = getContextForType('captureProxyESServiceEnabled', 'boolean')
        const migrationConsoleServiceEnabled = getContextForType('migrationConsoleServiceEnabled', 'boolean')
        const trafficReplayerServiceEnabled = getContextForType('trafficReplayerServiceEnabled', 'boolean')
        const trafficReplayerEnableClusterFGACAuth = getContextForType('trafficReplayerEnableClusterFGACAuth', 'boolean')
        const trafficReplayerTargetEndpoint = getContextForType('trafficReplayerTargetEndpoint', 'string')
        const trafficReplayerGroupId = getContextForType('trafficReplayerGroupId', 'string')
        const trafficReplayerExtraArgs = getContextForType('trafficReplayerExtraArgs', 'string')
        const trafficComparatorServiceEnabled = getContextForType('trafficComparatorServiceEnabled', 'boolean')
        const trafficComparatorJupyterServiceEnabled = getContextForType('trafficComparatorJupyterServiceEnabled', 'boolean')
        const captureProxyServiceEnabled = getContextForType('captureProxyServiceEnabled', 'boolean')
        const captureProxySourceEndpoint = getContextForType('captureProxySourceEndpoint', 'string')
        const elasticsearchServiceEnabled = getContextForType('elasticsearchServiceEnabled', 'boolean')
        const kafkaBrokerServiceEnabled = getContextForType('kafkaBrokerServiceEnabled', 'boolean')
        const kafkaZookeeperServiceEnabled = getContextForType('kafkaZookeeperServiceEnabled', 'boolean')
        const targetClusterEndpoint = getContextForType('targetClusterEndpoint', 'string')
        const targetClusterAccessSecurityGroup = getContextForType('targetClusterAccessSecurityGroup', 'string')
        const fetchMigrationEnabled = getContextForType('fetchMigrationEnabled', 'boolean')
        const dpPipelineTemplatePath = getContextForType('dpPipelineTemplatePath', 'string')
        const sourceClusterEndpoint = getContextForType('sourceClusterEndpoint', 'string')

        if (!stage) {
            throw new Error("Required context field 'stage' is not present")
        }
        if (addOnMigrationDeployId && vpcId) {
            console.warn("Addon deployments will use the original deployment 'vpcId' regardless of passed 'vpcId' values")
        }
        if (!domainName) {
            throw new Error("Domain name is not present and is a required field")
        }

        const engineVersion = getContextForType('engineVersion', 'string')
        if (engineVersion && engineVersion.startsWith("OS_")) {
            // Will accept a period delimited version string (i.e. 1.3) and return a proper EngineVersion
            version = EngineVersion.openSearch(engineVersion.substring(3))
        } else if (engineVersion && engineVersion.startsWith("ES_")) {
            version = EngineVersion.elasticsearch(engineVersion.substring(3))
        } else {
            throw new Error("Engine version is not present or does not match the expected format, i.e. OS_1.3 or ES_7.9")
        }

        if (openAccessPolicyEnabled) {
            const openPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                principals: [new AnyPrincipal()],
                actions: ["es:*"],
                resources: [`arn:aws:es:${region}:${account}:domain/${domainName}/*`]
            })
            accessPolicies = [openPolicy]
        } else {
            const accessPolicyJson = getContextForType('accessPolicies', 'object')
            accessPolicies = accessPolicyJson ? parseAccessPolicies(accessPolicyJson) : undefined
        }

        const tlsSecurityPolicyName = getContextForType('tlsSecurityPolicy', 'string')
        const tlsSecurityPolicy: TLSSecurityPolicy|undefined = tlsSecurityPolicyName ? TLSSecurityPolicy[tlsSecurityPolicyName as keyof typeof TLSSecurityPolicy] : undefined
        if (tlsSecurityPolicyName && !tlsSecurityPolicy) {
            throw new Error("Provided tlsSecurityPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.TLSSecurityPolicy.html")
        }

        const ebsVolumeTypeName = getContextForType('ebsVolumeType', 'string')
        const ebsVolumeType: EbsDeviceVolumeType|undefined = ebsVolumeTypeName ? EbsDeviceVolumeType[ebsVolumeTypeName as keyof typeof EbsDeviceVolumeType] : undefined
        if (ebsVolumeTypeName && !ebsVolumeType) {
            throw new Error("Provided ebsVolumeType does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_ec2.EbsDeviceVolumeType.html")
        }

        const domainRemovalPolicyName = getContextForType('domainRemovalPolicy', 'string')
        const domainRemovalPolicy = domainRemovalPolicyName ? RemovalPolicy[domainRemovalPolicyName as keyof typeof RemovalPolicy] : undefined
        if (domainRemovalPolicyName && !domainRemovalPolicy) {
            throw new Error("Provided domainRemovalPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.RemovalPolicy.html")
        }

        const deployId = addOnMigrationDeployId ? addOnMigrationDeployId : defaultDeployId

        // If enabled re-use existing VPC and/or associated resources or create new
        let networkStack: NetworkStack|undefined
        if (vpcEnabled || addOnMigrationDeployId) {
            networkStack = new NetworkStack(scope, `networkStack-${deployId}`, {
                vpcId: vpcId,
                availabilityZoneCount: availabilityZoneCount,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-NetworkInfra`,
                description: "This stack contains resources to create/manage networking for an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                addOnMigrationDeployId: addOnMigrationDeployId,
                ...props,
            })
            this.stacks.push(networkStack)
        }

        // There is an assumption here that for any deployment we will always have a target cluster, whether that be a
        // created cluster like below or an imported one
        let openSearchStack
        if (!targetClusterEndpoint) {
            openSearchStack = new OpenSearchDomainStack(scope, `openSearchDomainStack-${deployId}`, {
                version: version,
                domainName: domainName,
                dataNodeInstanceType: dataNodeType,
                dataNodes: dataNodeCount,
                dedicatedManagerNodeType: dedicatedManagerNodeType,
                dedicatedManagerNodeCount: dedicatedManagerNodeCount,
                warmInstanceType: warmNodeType,
                warmNodes: warmNodeCount,
                accessPolicies: accessPolicies,
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
                ebsVolumeType: ebsVolumeType,
                encryptionAtRestEnabled: encryptionAtRestEnabled,
                encryptionAtRestKmsKeyARN: encryptionAtRestKmsKeyARN,
                appLogEnabled: loggingAppLogEnabled,
                appLogGroup: loggingAppLogGroupARN,
                nodeToNodeEncryptionEnabled: noneToNodeEncryptionEnabled,
                vpc: networkStack ? networkStack.vpc : undefined,
                vpcSubnetIds: vpcSubnetIds,
                vpcSecurityGroupIds: vpcSecurityGroupIds,
                availabilityZoneCount: availabilityZoneCount,
                domainRemovalPolicy: domainRemovalPolicy,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-OpenSearchDomain`,
                description: "This stack contains resources to create/manage an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                addOnMigrationDeployId: addOnMigrationDeployId,
                ...props,
            });

            if (networkStack) {
                openSearchStack.addDependency(networkStack)
            }
            this.stacks.push(openSearchStack)
        }

        // Currently, placing a requirement on a VPC for a migration stack but this can be revisited
        let migrationStack
        let mskUtilityStack
        if (migrationAssistanceEnabled && networkStack && !addOnMigrationDeployId) {
            migrationStack = new MigrationAssistanceStack(scope, "migrationInfraStack", {
                vpc: networkStack.vpc,
                trafficComparatorEnabled: trafficComparatorServiceEnabled,
                mskImportARN: mskARN,
                mskEnablePublicEndpoints: mskEnablePublicEndpoints,
                mskRestrictPublicAccessTo: mskRestrictPublicAccessTo,
                mskRestrictPublicAccessType: mskRestrictPublicAccessType,
                mskBrokerNodeCount: mskBrokerNodeCount,
                stackName: `OSMigrations-${stage}-${region}-MigrationInfra`,
                description: "This stack contains resources to assist migrating an OpenSearch Service domain",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            migrationStack.addDependency(networkStack)
            this.stacks.push(migrationStack)

            mskUtilityStack = new MSKUtilityStack(scope, 'mskUtilityStack', {
                vpc: networkStack.vpc,
                mskEnablePublicEndpoints: mskEnablePublicEndpoints,
                stackName: `OSMigrations-${stage}-${region}-MSKUtility`,
                description: "This stack contains custom resources to add additional functionality to the MSK L1 construct",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            mskUtilityStack.addDependency(migrationStack)
            this.stacks.push(mskUtilityStack)
        }

        // Currently, placing a requirement on a VPC for a fetch migration stack but this can be revisited
        // TODO: Future work to provide orchestration between fetch migration and migration assistance
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
                ...props,
            })
            if (openSearchStack) {
                fetchMigrationStack.addDependency(openSearchStack)
            }
            fetchMigrationStack.addDependency(migrationStack)
            this.stacks.push(fetchMigrationStack)
        }

        let captureProxyESStack
        if (captureProxyESServiceEnabled && networkStack && mskUtilityStack) {
            captureProxyESStack = new CaptureProxyESStack(scope, "capture-proxy-es", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxyES`,
                description: "This stack contains resources for the Capture Proxy/Elasticsearch ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            captureProxyESStack.addDependency(mskUtilityStack)
            this.stacks.push(captureProxyESStack)
        }

        let trafficReplayerStack
        if ((trafficReplayerServiceEnabled && networkStack && migrationStack && mskUtilityStack) || (addOnMigrationDeployId && networkStack)) {
            trafficReplayerStack = new TrafficReplayerStack(scope, `traffic-replayer-${deployId}`, {
                vpc: networkStack.vpc,
                enableClusterFGACAuth: trafficReplayerEnableClusterFGACAuth,
                addOnMigrationDeployId: addOnMigrationDeployId,
                customTargetEndpoint: trafficReplayerTargetEndpoint,
                customKafkaGroupId: trafficReplayerGroupId,
                extraArgs: trafficReplayerExtraArgs,
                enableComparatorLink: trafficComparatorServiceEnabled,
                stackName: `OSMigrations-${stage}-${region}-${deployId}-TrafficReplayer`,
                description: "This stack contains resources for the Traffic Replayer ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            if (mskUtilityStack) {
                trafficReplayerStack.addDependency(mskUtilityStack)
            }
            if (migrationStack) {
                trafficReplayerStack.addDependency(migrationStack)
            }
            if (openSearchStack) {
                trafficReplayerStack.addDependency(openSearchStack)
            }
            trafficReplayerStack.addDependency(networkStack)
            this.stacks.push(trafficReplayerStack)
        }

        let trafficComparatorStack
        if (trafficComparatorServiceEnabled && networkStack && migrationStack) {
            trafficComparatorStack = new TrafficComparatorStack(scope, "traffic-comparator", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-TrafficComparator`,
                description: "This stack contains resources for the Traffic Comparator ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            trafficComparatorStack.addDependency(migrationStack)
            this.stacks.push(trafficComparatorStack)
        }

        let trafficComparatorJupyterStack
        if (trafficComparatorJupyterServiceEnabled && networkStack && trafficComparatorStack) {
            trafficComparatorJupyterStack = new TrafficComparatorJupyterStack(scope, "traffic-comparator-jupyter", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-TrafficComparatorJupyter`,
                description: "This stack contains resources for creating a Jupyter Notebook to perform analysis on Traffic Comparator output as an ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            trafficComparatorJupyterStack.addDependency(trafficComparatorStack)
            this.stacks.push(trafficComparatorJupyterStack)
        }

        let elasticsearchStack
        if (elasticsearchServiceEnabled && networkStack && migrationStack) {
            elasticsearchStack = new ElasticsearchStack(scope, "elasticsearch", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-Elasticsearch`,
                description: "This stack contains resources for a testing mock Elasticsearch single node cluster ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            elasticsearchStack.addDependency(migrationStack)
            this.stacks.push(elasticsearchStack)
        }

        let captureProxyStack
        if (captureProxyServiceEnabled && networkStack && mskUtilityStack) {
            captureProxyStack = new CaptureProxyStack(scope, "capture-proxy", {
                vpc: networkStack.vpc,
                customSourceClusterEndpoint: captureProxySourceEndpoint,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxy`,
                description: "This stack contains resources for the Capture Proxy ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            if (elasticsearchStack) {
                captureProxyStack.addDependency(elasticsearchStack)
            }
            captureProxyStack.addDependency(mskUtilityStack)
            this.stacks.push(captureProxyStack)
        }

        let kafkaBrokerStack
        if (kafkaBrokerServiceEnabled && networkStack && migrationStack) {
            kafkaBrokerStack = new KafkaBrokerStack(scope, "kafka-broker", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-KafkaBroker`,
                description: "This stack contains resources for the Kafka Broker ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            kafkaBrokerStack.addDependency(migrationStack)
            this.stacks.push(kafkaBrokerStack)
        }

        let kafkaZookeeperStack
        if (kafkaZookeeperServiceEnabled && networkStack && migrationStack) {
            kafkaZookeeperStack = new KafkaZookeeperStack(scope, "kafka-zookeeper", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-KafkaZookeeper`,
                description: "This stack contains resources for the Kafka Zookeeper ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            kafkaZookeeperStack.addDependency(migrationStack)
            this.stacks.push(kafkaZookeeperStack)
        }

        let migrationConsoleStack
        if (migrationConsoleServiceEnabled && networkStack && mskUtilityStack) {
            migrationConsoleStack = new MigrationConsoleStack(scope, "migration-console", {
                vpc: networkStack.vpc,
                fetchMigrationEnabled: fetchMigrationEnabled,
                stackName: `OSMigrations-${stage}-${region}-MigrationConsole`,
                description: "This stack contains resources for the Migration Console ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                ...props,
            })
            // To enable the Migration Console to make requests to other service endpoints with Service Connect,
            // it must be deployed after these services
            if (captureProxyESStack) {
                migrationConsoleStack.addDependency(captureProxyESStack)
            }
            if (captureProxyStack) {
                migrationConsoleStack.addDependency(captureProxyStack)
            }
            if (elasticsearchStack) {
                migrationConsoleStack.addDependency(elasticsearchStack)
            }
            if (fetchMigrationStack) {
                migrationConsoleStack.addDependency(fetchMigrationStack)
            }
            if (openSearchStack) {
                migrationConsoleStack.addDependency(openSearchStack)
            }
            migrationConsoleStack.addDependency(mskUtilityStack)
            this.stacks.push(migrationConsoleStack)
        }

        if (props.migrationsAppRegistryARN) {
            this.addStacksToAppRegistry(scope, props.migrationsAppRegistryARN, this.stacks)
        }

        function getContextForType(optionName: string, expectedType: string): any {
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
                    return JSON.parse(option)
                }
            }
            // Values provided by the cdk.context.json should be of the desired type
            if (typeof option !== expectedType) {
                throw new Error(`Type provided by cdk.context.json for ${optionName} was ${typeof option} but expected ${expectedType}`)
            }
            return option
        }

        function parseAccessPolicies(jsonObject: { [x: string]: any; }): PolicyStatement[] {
            let accessPolicies: PolicyStatement[] = []
            const statements = jsonObject['Statement']
            if (!statements || statements.length < 1) {
                throw new Error ("Provided accessPolicies JSON must have the 'Statement' element present and not be empty, for reference https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_statement.html")
            }
            // Access policies can provide a single Statement block or an array of Statement blocks
            if (Array.isArray(statements)) {
                for (let statementBlock of statements) {
                    const statement = PolicyStatement.fromJson(statementBlock)
                    accessPolicies.push(statement)
                }
            }
            else {
                const statement = PolicyStatement.fromJson(statements)
                accessPolicies.push(statement)
            }
            return accessPolicies
        }

    }
}