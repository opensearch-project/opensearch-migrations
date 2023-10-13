import {Construct} from "constructs";
import {RemovalPolicy, Stack, StackProps} from "aws-cdk-lib";
import {OpenSearchDomainStack} from "./opensearch-domain-stack";
import {EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import {EbsDeviceVolumeType} from "aws-cdk-lib/aws-ec2";
import {AnyPrincipal, Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import * as defaultValuesJson from "../default-values.json"
import {NetworkStack} from "./network-stack";
import {MigrationAssistanceStack} from "./migration-assistance-stack";
import {HistoricalCaptureStack} from "./historical-capture-stack";
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

export interface StackPropsExt extends StackProps {
    readonly stage: string
}

export class StackComposer {
    public stacks: Stack[] = [];

    constructor(scope: Construct, props: StackPropsExt) {

        const stage = props.stage
        const account = props.env?.account
        const region = props.env?.region

        let version: EngineVersion
        let accessPolicies: PolicyStatement[]|undefined
        const defaultValues: { [x: string]: (any); } = defaultValuesJson
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
        const mskBrokerNodeCount = getContextForType('mskBrokerNodeCount', 'number')
        const captureProxyESServiceEnabled = getContextForType('captureProxyESServiceEnabled', 'boolean')
        const migrationConsoleServiceEnabled = getContextForType('migrationConsoleServiceEnabled', 'boolean')
        const trafficReplayerServiceEnabled = getContextForType('trafficReplayerServiceEnabled', 'boolean')
        const trafficComparatorServiceEnabled = getContextForType('trafficComparatorServiceEnabled', 'boolean')
        const trafficComparatorJupyterServiceEnabled = getContextForType('trafficComparatorJupyterServiceEnabled', 'boolean')
        const captureProxyServiceEnabled = getContextForType('captureProxyServiceEnabled', 'boolean')
        const elasticsearchServiceEnabled = getContextForType('kafkaBrokerServiceEnabled', 'boolean')
        const kafkaBrokerServiceEnabled = getContextForType('migrationConsoleEnabled', 'boolean')
        const kafkaZookeeperServiceEnabled = getContextForType('kafkaZookeeperServiceEnabled', 'boolean')
        const sourceClusterEndpoint = getContextForType('sourceClusterEndpoint', 'string')
        const historicalCaptureEnabled = getContextForType('historicalCaptureEnabled', 'boolean')
        const logstashConfigFilePath = getContextForType('logstashConfigFilePath', 'string')

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

        // If enabled re-use existing VPC and/or associated resources or create new
        let networkStack: NetworkStack|undefined
        if (vpcEnabled) {
            networkStack = new NetworkStack(scope, 'networkStack', {
                vpcId: vpcId,
                availabilityZoneCount: availabilityZoneCount,
                stackName: `OSMigrations-${stage}-${region}-NetworkInfra`,
                description: "This stack contains resources to create/manage networking for an OpenSearch Service domain",
                ...props,
            })
            this.stacks.push(networkStack)
        }

        const openSearchStack = new OpenSearchDomainStack(scope, 'openSearchDomainStack', {
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
            stackName: `OSMigrations-${stage}-${region}-OpenSearchDomain`,
            description: "This stack contains resources to create/manage an OpenSearch Service domain",
            ...props,
        });

        if (networkStack) {
            openSearchStack.addDependency(networkStack)
        }
        this.stacks.push(openSearchStack)

        // Currently, placing a requirement on a VPC for a migration stack but this can be revisited
        let migrationStack
        let mskUtilityStack
        if (migrationAssistanceEnabled && networkStack) {
            migrationStack = new MigrationAssistanceStack(scope, "migrationAssistanceStack", {
                vpc: networkStack.vpc,
                mskImportARN: mskARN,
                mskEnablePublicEndpoints: mskEnablePublicEndpoints,
                mskBrokerNodeCount: mskBrokerNodeCount,
                stackName: `OSMigrations-${stage}-${region}-MigrationInfra`,
                description: "This stack contains resources to assist migrating an OpenSearch Service domain",
                ...props,
            })
            migrationStack.addDependency(networkStack)
            this.stacks.push(migrationStack)

            mskUtilityStack = new MSKUtilityStack(scope, 'mskUtilityStack', {
                vpc: networkStack.vpc,
                mskEnablePublicEndpoints: mskEnablePublicEndpoints,
                stackName: `OSMigrations-${stage}-${region}-MSKUtility`,
                description: "This stack contains custom resources to add additional functionality to the MSK L1 construct",
                ...props,
            })
            mskUtilityStack.addDependency(networkStack)
            mskUtilityStack.addDependency(migrationStack)
            this.stacks.push(mskUtilityStack)
        }

        let captureProxyESStack
        if (captureProxyESServiceEnabled && networkStack && migrationStack && mskUtilityStack) {
            captureProxyESStack = new CaptureProxyESStack(scope, "capture-proxy-es", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxyES`,
                description: "This stack contains resources for the Capture Proxy/Elasticsearch ECS service",
                ...props,
            })
            captureProxyESStack.addDependency(mskUtilityStack)
            captureProxyESStack.addDependency(migrationStack)
            captureProxyESStack.addDependency(networkStack)
            this.stacks.push(captureProxyESStack)
        }

        let migrationConsoleStack
        if (migrationConsoleServiceEnabled && networkStack && openSearchStack && migrationStack && mskUtilityStack) {
            migrationConsoleStack = new MigrationConsoleStack(scope, "migration-console", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-MigrationConsole`,
                description: "This stack contains resources for the Migration Console ECS service",
                ...props,
            })
            // To enable the Migration Console to make requests to the Capture Proxy with Service Connect,
            // it should be deployed after the Capture Proxy
            if (captureProxyESStack) {
                migrationConsoleStack.addDependency(captureProxyESStack)
            }
            migrationConsoleStack.addDependency(mskUtilityStack)
            migrationConsoleStack.addDependency(migrationStack)
            migrationConsoleStack.addDependency(openSearchStack)
            migrationConsoleStack.addDependency(networkStack)
            this.stacks.push(migrationConsoleStack)
        }

        let trafficReplayerStack
        if (trafficReplayerServiceEnabled && networkStack && openSearchStack && migrationStack && mskUtilityStack) {
            trafficReplayerStack = new TrafficReplayerStack(scope, "traffic-replayer", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-TrafficReplayer`,
                description: "This stack contains resources for the Traffic Replayer ECS service",
                ...props,
            })
            trafficReplayerStack.addDependency(mskUtilityStack)
            trafficReplayerStack.addDependency(migrationStack)
            trafficReplayerStack.addDependency(openSearchStack)
            trafficReplayerStack.addDependency(networkStack)
            this.stacks.push(trafficReplayerStack)
        }

        let trafficComparatorStack
        if (trafficComparatorServiceEnabled && networkStack && migrationStack) {
            trafficComparatorStack = new TrafficComparatorStack(scope, "traffic-comparator", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-TrafficComparator`,
                description: "This stack contains resources for the Traffic Comparator ECS service",
                ...props,
            })
            trafficComparatorStack.addDependency(migrationStack)
            trafficComparatorStack.addDependency(networkStack)
            this.stacks.push(trafficComparatorStack)
        }

        let trafficComparatorJupyterStack
        if (trafficComparatorJupyterServiceEnabled && networkStack && migrationStack) {
            trafficComparatorJupyterStack = new TrafficComparatorJupyterStack(scope, "traffic-comparator-jupyter", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-TrafficComparatorJupyter`,
                description: "This stack contains resources for the Traffic Comparator Jupyter ECS service",
                ...props,
            })
            trafficComparatorJupyterStack.addDependency(migrationStack)
            trafficComparatorJupyterStack.addDependency(networkStack)
            this.stacks.push(trafficComparatorJupyterStack)
        }

        let captureProxyStack
        if (captureProxyServiceEnabled && networkStack && migrationStack && mskUtilityStack) {
            captureProxyStack = new CaptureProxyStack(scope, "capture-proxy", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-CaptureProxy`,
                description: "This stack contains resources for the Capture Proxy ECS service",
                ...props,
            })
            captureProxyStack.addDependency(mskUtilityStack)
            captureProxyStack.addDependency(migrationStack)
            captureProxyStack.addDependency(networkStack)
            this.stacks.push(captureProxyStack)
        }

        let elasticsearchStack
        if (elasticsearchServiceEnabled && networkStack && migrationStack) {
            elasticsearchStack = new ElasticsearchStack(scope, "elasticsearch", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-Elasticsearch`,
                description: "This stack contains resources for the Elasticsearch ECS service",
                ...props,
            })
            elasticsearchStack.addDependency(migrationStack)
            elasticsearchStack.addDependency(networkStack)
            this.stacks.push(elasticsearchStack)
        }

        let kafkaBrokerStack
        if (kafkaBrokerServiceEnabled && networkStack && migrationStack) {
            kafkaBrokerStack = new KafkaBrokerStack(scope, "kafka-broker", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-KafkaBroker`,
                description: "This stack contains resources for the Kafka Broker ECS service",
                ...props,
            })
            kafkaBrokerStack.addDependency(migrationStack)
            kafkaBrokerStack.addDependency(networkStack)
            this.stacks.push(kafkaBrokerStack)
        }

        let kafkaZookeeperStack
        if (kafkaBrokerServiceEnabled && networkStack && migrationStack) {
            kafkaZookeeperStack = new KafkaZookeeperStack(scope, "kafka-zookeeper", {
                vpc: networkStack.vpc,
                stackName: `OSMigrations-${stage}-${region}-KafkaZookeeper`,
                description: "This stack contains resources for the Kafka Zookeeper ECS service",
                ...props,
            })
            kafkaZookeeperStack.addDependency(migrationStack)
            kafkaZookeeperStack.addDependency(networkStack)
            this.stacks.push(kafkaZookeeperStack)
        }

        // Currently, placing a requirement on a VPC for a historical capture stack but this can be revisited
        // Note: Future work to provide orchestration between historical capture and migration assistance as the current
        // state will potentially have both stacks trying to add the same data
        if (historicalCaptureEnabled && networkStack) {
            const historicalCaptureStack = new HistoricalCaptureStack(scope, "historicalCaptureStack", {
                vpc: networkStack.vpc,
                logstashConfigFilePath: logstashConfigFilePath,
                sourceEndpoint: sourceClusterEndpoint,
                stackName: `OSMigrations-${stage}-${region}-HistoricalCapture`,
                description: "This stack contains resources to assist migrating historical data to an OpenSearch Service domain",
                ...props,
            })
            historicalCaptureStack.addDependency(networkStack)
            historicalCaptureStack.addDependency(openSearchStack)
            this.stacks.push(historicalCaptureStack)
        }


        function getContextForType(optionName: string, expectedType: string): any {
            const block = scope.node.tryGetContext("dev-deploy-1")
            const option = scope.node.tryGetContext(optionName)

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
                for (let i = 0; i < statements.length; i++) {
                    const statement = PolicyStatement.fromJson(statements[i])
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