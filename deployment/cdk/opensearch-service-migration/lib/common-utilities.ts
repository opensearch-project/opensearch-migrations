import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {Construct} from "constructs";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {RemovalPolicy} from "aws-cdk-lib";
import { IApplicationLoadBalancer } from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { ICertificate } from "aws-cdk-lib/aws-certificatemanager";
import { IStringParameter, StringParameter } from "aws-cdk-lib/aws-ssm";
import * as forge from 'node-forge';
import * as yargs from 'yargs';
import { ClusterYaml } from "./migration-services-yaml";


// parseAndMergeArgs, see @common-utilities.test.ts for an example of different cases
export function parseAndMergeArgs(baseCommand: string, extraArgs?: string): string {
    if (!extraArgs) {
        return baseCommand;
    }

    // Extract command prefix
    const commandPrefix = baseCommand.substring(0, baseCommand.indexOf('--')).trim();
    const baseArgs = baseCommand.substring(baseCommand.indexOf('--'));

    // Parse base command
    const baseYargsConfig = {
        parserConfiguration: {
            'camel-case-expansion': false,
            'boolean-negation': false,
        }
    };

    const baseArgv = yargs(baseArgs)
        .parserConfiguration(baseYargsConfig.parserConfiguration)
        .parse();

    // Parse extra args if provided
    const extraYargsConfig = {
        parserConfiguration: {
            'camel-case-expansion': false,
            'boolean-negation': true,
        }
    };

    const extraArgv = extraArgs
        ? yargs(extraArgs.split(' '))
            .parserConfiguration(extraYargsConfig.parserConfiguration)
            .parse()
        : {};

    // Merge arguments
    const mergedArgv: { [key: string]: unknown } = { ...baseArgv };
    for (const [key, value] of Object.entries(extraArgv)) {
        if (key !== '_' && key !== '$0') {
            if (!value &&
                typeof value === 'boolean' &&
                (
                    typeof (baseArgv as any)[key] === 'boolean' ||
                    (typeof (baseArgv as any)[`no-${key}`] != 'boolean' && typeof (baseArgv as any)[`no-${key}`])
                )
            ) {
                delete mergedArgv[key];
            } else {
                mergedArgv[key] = value;
            }
        }
    }

    // Reconstruct command
    const mergedArgs = Object.entries(mergedArgv)
        .filter(([key]) => key !== '_' && key !== '$0')
        .map(([key, value]) => {
            if (typeof value === 'boolean') {
                return value ? `--${key}` : `--no-${key}`;
            }
            return `--${key} ${value}`;
        })
        .join(' ');

    let fullCommand = `${commandPrefix} ${mergedArgs}`.trim()
    return fullCommand;
}

export function getTargetPasswordAccessPolicy(targetPasswordSecretArn: string): PolicyStatement {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [targetPasswordSecretArn],
        actions: [
            "secretsmanager:GetSecretValue"
        ]
    })
}

export function createOpenSearchIAMAccessPolicy(partition: string, region: string, accountId: string): PolicyStatement {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [`arn:${partition}:es:${region}:${accountId}:domain/*`],
        actions: [
            "es:ESHttp*"
        ]
    })
}

export function createOpenSearchServerlessIAMAccessPolicy(partition: string, region: string, accountId: string): PolicyStatement {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [`arn:${partition}:aoss:${region}:${accountId}:collection/*`],
        actions: [
            "aoss:APIAccessAll"
        ]
    })
}

export function createMSKConsumerIAMPolicies(scope: Construct, partition: string, region: string, accountId: string, stage: string, deployId: string): PolicyStatement[] {
    const mskClusterARN = getMigrationStringParameterValue(scope, { parameter: MigrationSSMParameter.MSK_CLUSTER_ARN, stage, defaultDeployId: deployId });
    const mskClusterName = getMigrationStringParameterValue(scope, { parameter: MigrationSSMParameter.MSK_CLUSTER_NAME, stage, defaultDeployId: deployId });
    const mskClusterConnectPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterARN],
        actions: [
            "kafka-cluster:Connect"
        ]
    })
    const mskClusterAllTopicArn = `arn:${partition}:kafka:${region}:${accountId}:topic/${mskClusterName}/*`
    const mskTopicConsumerPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterAllTopicArn],
        actions: [
            "kafka-cluster:DescribeTopic",
            "kafka-cluster:ReadData"
        ]
    })
    const mskClusterAllGroupArn = `arn:${partition}:kafka:${region}:${accountId}:group/${mskClusterName}/*`
    const mskConsumerGroupPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterAllGroupArn],
        actions: [
            "kafka-cluster:AlterGroup",
            "kafka-cluster:DescribeGroup"
        ]
    })
    return [mskClusterConnectPolicy, mskTopicConsumerPolicy, mskConsumerGroupPolicy]

}

export function createMSKProducerIAMPolicies(scope: Construct, partition: string, region: string, accountId: string, stage: string, deployId: string): PolicyStatement[] {
    const mskClusterARN = getMigrationStringParameterValue(scope, { parameter: MigrationSSMParameter.MSK_CLUSTER_ARN, stage, defaultDeployId: deployId });
    const mskClusterName = getMigrationStringParameterValue(scope, { parameter: MigrationSSMParameter.MSK_CLUSTER_NAME, stage, defaultDeployId: deployId });
    const mskClusterConnectPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterARN],
        actions: [
            "kafka-cluster:Connect"
        ]
    })
    const mskClusterAllTopicArn = `arn:${partition}:kafka:${region}:${accountId}:topic/${mskClusterName}/*`
    const mskTopicProducerPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterAllTopicArn],
        actions: [
            "kafka-cluster:CreateTopic",
            "kafka-cluster:DescribeTopic",
            "kafka-cluster:WriteData"
        ]
    })
    return [mskClusterConnectPolicy, mskTopicProducerPolicy]
}

export function createAwsDistroForOtelPushInstrumentationPolicy(): PolicyStatement {
    // see https://aws-otel.github.io/docs/setup/permissions
    return new PolicyStatement( {
        effect: Effect.ALLOW,
        resources: ["*"],
        actions: [
            "logs:PutLogEvents",
            "logs:CreateLogGroup",
            "logs:CreateLogStream",
            "logs:DescribeLogStreams",
            "logs:DescribeLogGroups",
            "logs:PutRetentionPolicy",
            "xray:PutTraceSegments",
            "xray:PutTelemetryRecords",
            "xray:GetSamplingRules",
            "xray:GetSamplingTargets",
            "xray:GetSamplingStatisticSummaries",
            "ssm:GetParameters"
        ]
    })
}

export function createDefaultECSTaskRole(scope: Construct, serviceName: string): Role {
    const serviceTaskRole = new Role(scope, `${serviceName}-TaskRole`, {
        assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
        description: 'ECS Service Task Role'
    });
    // Add default Task Role policy to allow exec and writing logs
    serviceTaskRole.addToPolicy(new PolicyStatement({
        effect: Effect.ALLOW,
        resources: ['*'],
        actions: [
            "logs:CreateLogGroup",
            "logs:CreateLogStream",
            "logs:DescribeLogGroups",
            "logs:DescribeLogStreams",
            "logs:PutLogEvents",
            "ssmmessages:CreateControlChannel",
            "ssmmessages:CreateDataChannel",
            "ssmmessages:OpenControlChannel",
            "ssmmessages:OpenDataChannel"
        ]
    }))
    return serviceTaskRole
}

export function validateFargateCpuArch(cpuArch?: string): CpuArchitecture {
    const desiredArch = cpuArch ? cpuArch : process.arch
    const desiredArchUpper = desiredArch.toUpperCase()

    if (desiredArchUpper === "X86_64" || desiredArchUpper === "X64") {
        return CpuArchitecture.X86_64
    } else if (desiredArchUpper === "ARM64") {
        return CpuArchitecture.ARM64
    } else {
        if (cpuArch) {
            throw new Error(`Unknown Fargate cpu architecture provided: ${desiredArch}`)
        }
        else {
            throw new Error(`Unsupported process cpu architecture detected: ${desiredArch}, CDK requires X64 or ARM64 for Docker image compatability`)
        }
    }
}

export function parseRemovalPolicy(optionName: string, policyNameString?: string): RemovalPolicy|undefined {
    const policy = policyNameString ? RemovalPolicy[policyNameString as keyof typeof RemovalPolicy] : undefined
    if (policyNameString && !policy) {
        throw new Error(`Provided '${optionName}' with value '${policyNameString}' does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.RemovalPolicy.html`)
    }
    return policy
}


export type ALBConfig = NewALBListenerConfig;

export interface NewALBListenerConfig {
    alb: IApplicationLoadBalancer,
    albListenerCert: ICertificate,
    albListenerPort?: number,
}

export function isNewALBListenerConfig(config: ALBConfig): config is NewALBListenerConfig {
    const parsed = config as NewALBListenerConfig;
    return parsed.alb !== undefined && parsed.albListenerCert !== undefined;
}

export function hashStringSHA256(message: string): string {
    const md = forge.md.sha256.create();
    md.update(message);
    return md.digest().toHex();
}

export interface MigrationSSMConfig {
    parameter: MigrationSSMParameter,
    stage: string,
    defaultDeployId: string
}

export function createMigrationStringParameter(scope: Construct, stringValue: string, props: MigrationSSMConfig) {
    return new StringParameter(scope, `SSMParameter${props.parameter.charAt(0).toUpperCase() + props.parameter.slice(1)}`, {
        parameterName: getMigrationStringParameterName(props),
        stringValue: stringValue,
        description: `Opensearch migration SSM parameter for ${props.parameter} with stage ${props.stage} and deploy id ${props.defaultDeployId}`,
    });
}

export function getMigrationStringParameter(scope: Construct, props: MigrationSSMConfig): IStringParameter {
    return StringParameter.fromStringParameterName(scope, `SSMParameter${props.parameter.charAt(0).toUpperCase() + props.parameter.slice(1)}`,
        getMigrationStringParameterName(props));
}

export function getMigrationStringParameterValue(scope: Construct, props: MigrationSSMConfig): string {
    return StringParameter.valueForTypedStringParameterV2(scope, getMigrationStringParameterName(props));
}

export function getCustomStringParameterValue(scope: Construct, parameterName: string): string {
    return StringParameter.valueForTypedStringParameterV2(scope, parameterName);
}

export function getMigrationStringParameterName(props: MigrationSSMConfig): string {
    return `/migration/${props.stage}/${props.defaultDeployId}/${props.parameter}`;
}

export enum MigrationSSMParameter {
    SOURCE_PROXY_URL = 'albSourceProxyUrl',
    SOURCE_PROXY_URL_ALIAS = 'albSourceProxyUrlAlias',
    TARGET_PROXY_URL = 'albTargetProxyUrl',
    TARGET_PROXY_URL_ALIAS = 'albTargetProxyUrlAlias',
    MIGRATION_LISTENER_URL = 'albMigrationListenerUrl',
    MIGRATION_LISTENER_URL_ALIAS = 'albMigrationListenerUrlAlias',
    ARTIFACT_S3_ARN = 'artifactS3Arn',
    FETCH_MIGRATION_COMMAND = 'fetchMigrationCommand',
    FETCH_MIGRATION_TASK_DEF_ARN = 'fetchMigrationTaskDefArn',
    FETCH_MIGRATION_TASK_EXEC_ROLE_ARN = 'fetchMigrationTaskExecRoleArn',
    FETCH_MIGRATION_TASK_ROLE_ARN = 'fetchMigrationTaskRoleArn',
    KAFKA_BROKERS = 'kafkaBrokers',
    MSK_CLUSTER_ARN = 'mskClusterARN',
    MSK_CLUSTER_NAME = 'mskClusterName',
    OS_ACCESS_SECURITY_GROUP_ID = 'osAccessSecurityGroupId',
    OS_CLUSTER_ENDPOINT = 'osClusterEndpoint',
    OS_USER_AND_SECRET_ARN = 'osUserAndSecretArn',
    OSI_PIPELINE_LOG_GROUP_NAME = 'osiPipelineLogGroupName',
    OSI_PIPELINE_ROLE_ARN = 'osiPipelineRoleArn',
    SHARED_LOGS_SECURITY_GROUP_ID = 'sharedLogsSecurityGroupId',
    SHARED_LOGS_EFS_ID = 'sharedLogsEfsId',
    SOURCE_CLUSTER_ENDPOINT = 'sourceClusterEndpoint',
    SERVICE_SECURITY_GROUP_ID = 'serviceSecurityGroupId',
    SERVICES_YAML_FILE = 'servicesYamlFile',
    TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID = 'trafficStreamSourceAccessSecurityGroupId',
    VPC_ID = 'vpcId',
}


export class ClusterNoAuth {};

export class ClusterSigV4Auth {
    region?: string;
    serviceSigningName?: string;
    constructor({region, serviceSigningName: service}: {region: string, serviceSigningName: string}) {
        this.region = region;
        this.serviceSigningName = service;
    }
}

export class ClusterBasicAuth {
    username: string;
    password?: string;
    password_from_secret_arn?: string;

    constructor({
        username,
        password,
        password_from_secret_arn,
    }: {
        username: string;
        password?: string;
        password_from_secret_arn?: string;
    }) {
        this.username = username;
        this.password = password;
        this.password_from_secret_arn = password_from_secret_arn;

        // Validation: Exactly one of password or password_from_secret_arn must be provided
        if ((password && password_from_secret_arn) || (!password && !password_from_secret_arn)) {
            throw new Error('Exactly one of password or password_from_secret_arn must be provided');
        }
    }
}

export class ClusterAuth {
    basicAuth?: ClusterBasicAuth
    noAuth?: ClusterNoAuth
    sigv4?: ClusterSigV4Auth

    constructor({basicAuth, noAuth, sigv4}: {basicAuth?: ClusterBasicAuth, noAuth?: ClusterNoAuth, sigv4?: ClusterSigV4Auth}) {
        this.basicAuth = basicAuth;
        this.noAuth = noAuth;
        this.sigv4 = sigv4;
    }

    validate() {
        const numDefined = (this.basicAuth? 1 : 0) + (this.noAuth? 1 : 0) + (this.sigv4? 1 : 0)
        if (numDefined != 1) {
            throw new Error(`Exactly one authentication method can be defined. ${numDefined} are currently set.`)
        }
    }

    toDict() {
        if (this.basicAuth) {
            return {basic_auth: this.basicAuth};
        }
        if (this.noAuth) {
            return {no_auth: ""};
        }
        if (this.sigv4) {
            return {sigv4: this.sigv4};
        }
        return {};
    }
}

function getBasicClusterAuth(basicAuthObject: { [key: string]: any }): ClusterBasicAuth {
    // Destructure and validate the input object
    const { username, password, passwordFromSecretArn } = basicAuthObject;
    // Ensure the required 'username' field is present
    if (typeof username !== 'string' || !username) {
        throw new Error('Invalid input: "username" must be a non-empty string');
    }
    // Ensure that exactly one of 'password' or 'passwordFromSecretArn' is provided
    const hasPassword = typeof password === 'string' && password.trim() !== '';
    const hasPasswordFromSecretArn = typeof passwordFromSecretArn === 'string' && passwordFromSecretArn.trim() !== '';
    if ((hasPassword && hasPasswordFromSecretArn) || (!hasPassword && !hasPasswordFromSecretArn)) {
        throw new Error('Exactly one of "password" or "passwordFromSecretArn" must be provided');
    }
    return new ClusterBasicAuth({
        username,
        password: hasPassword ? password : undefined,
        password_from_secret_arn: hasPasswordFromSecretArn ? passwordFromSecretArn : undefined,
    });
}

function getSigV4ClusterAuth(sigv4AuthObject: { [key: string]: any }): ClusterSigV4Auth {
    // Destructure and validate the input object
    const { serviceSigningName, region } = sigv4AuthObject;

    // Create and return the ClusterSigV4Auth object
    return new ClusterSigV4Auth({serviceSigningName, region});
}

// Function to parse and validate auth object
function parseAuth(json: any): ClusterAuth | null {
    if (json.type === 'basic' && typeof json.username === 'string' && (typeof json.password === 'string' || typeof json.passwordFromSecretArn === 'string') && !(typeof json.password === 'string' && typeof json.passwordFromSecretArn === 'string')) {
        return new ClusterAuth({basicAuth: getBasicClusterAuth(json)});
    } else if (json.type === 'sigv4' && typeof json.region === 'string' && typeof json.serviceSigningName === 'string') {
        return new ClusterAuth({sigv4: getSigV4ClusterAuth(json)});
    } else if (json.type === 'none') {
        return new ClusterAuth({noAuth: new ClusterNoAuth()});
    }
    return null; // Invalid auth type
}

export function parseClusterDefinition(json: any): ClusterYaml {
    const endpoint = json.endpoint
    const version = json.version
    if (!endpoint) {
        throw new Error('Missing required field in cluster definition: endpoint')
    }
    const auth = parseAuth(json.auth)
    if (!auth) {
        throw new Error(`Invalid auth type when parsing cluster definition: ${json.auth.type}`)
    }
    return new ClusterYaml({endpoint, version, auth})
}