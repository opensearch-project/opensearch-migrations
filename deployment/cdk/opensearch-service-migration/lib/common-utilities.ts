import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {Construct} from "constructs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {ContainerImage, CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {RemovalPolicy, SecretValue, Stack} from "aws-cdk-lib";
import {IStringParameter, StringParameter} from "aws-cdk-lib/aws-ssm";
import {Secret} from "aws-cdk-lib/aws-secretsmanager";
import * as forge from 'node-forge';
import {ClusterYaml, SnapshotYaml} from "./migration-services-yaml";
import {CdkLogger} from "./cdk-logger";
import {mkdtempSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {execSync} from 'node:child_process';

export const MAX_IAM_ROLE_NAME_LENGTH = 64;
export const MAX_STAGE_NAME_LENGTH = 15;
export enum ClusterType {
    SOURCE = 'source',
    TARGET = 'target',
}
export enum ContainerEnvVarNames {
    TARGET_USERNAME = 'TARGET_USERNAME',
    TARGET_PASSWORD = 'TARGET_PASSWORD',
}

export function getSecretAccessPolicy(secretArn: string): PolicyStatement {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [secretArn],
        actions: [
            "secretsmanager:GetSecretValue"
        ]
    })
}

export function appendArgIfNotInExtraArgs(
    baseCommand: string,
    extraArgsDict: Record<string, string[]>,
    arg: string,
    value: string | null = null,
): string {
    if (extraArgsDict[arg] === undefined) {
        // If not present, append the argument and value (only append value if it exists)
        baseCommand = value !== null ? baseCommand.concat(" ", arg, " ", value) : baseCommand.concat(" ", arg);
    }
    return baseCommand;
}

export function parseArgsToDict(argString: string | undefined): Record<string, string[]> {
    const args: Record<string, string[]> = {};
    if (argString === undefined) {
        return args;
    }
    // Split based on '--' at the start of the string or preceded by whitespace, use non-capturing groups to include -- in parts
    const parts = argString.split(/(?=\s--|^--)/).filter(Boolean);

    parts.forEach(part => {
        const trimmedPart = part.trim();
        if (trimmedPart.length === 0) return; // Skip empty parts

        // Use a regular expression to find the first whitespace character
        const firstWhitespaceMatch = /\s/.exec(trimmedPart);
        const firstWhitespaceIndex = firstWhitespaceMatch?.index;

        const key = firstWhitespaceIndex === undefined ? trimmedPart : trimmedPart.slice(0, firstWhitespaceIndex).trim();
        const value = firstWhitespaceIndex === undefined ? '' : trimmedPart.slice(firstWhitespaceIndex + 1).trim();

        // Validate the key starts with -- followed by a non-whitespace characters
        if (/^--\S+/.test(key)) {
            if (args[key] !== undefined) {
                args[key].push(value);
            } else {
                args[key] = [value];
            }
        } else {
            throw new Error(`Invalid argument key: '${key}'. Argument keys must start with '--' and contain no spaces.`);
        }
    });
    if (argString.trim() && !args) {
        throw new Error(`Unable to parse args provided: '${argString}'`);
    }

    return args;
}

export function createAllAccessOpenSearchIAMAccessPolicy(): PolicyStatement {
    // Allow all access to opensearch domains in any account/region
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: ["*"],
        actions: [
            "es:ESHttp*"
        ]
    })
}

export function createAllAccessOpenSearchServerlessIAMAccessPolicy(): PolicyStatement {
    // Allow all access to collections in any account/region
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: ["*"],
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

export function createECSTaskRole(scope: Construct, serviceName: string, region: string, stage: string): Role {
    let taskRoleName = `OSMigrations-${stage}-${region}-${serviceName}-task`
    const excessCharacters = taskRoleName.length - MAX_IAM_ROLE_NAME_LENGTH
    if (excessCharacters > 0) {
        if (excessCharacters > serviceName.length) {
            throw new Error(`Unexpected ECS task role name length for proposed name: '${taskRoleName}' could not be reasonably truncated 
                below ${MAX_IAM_ROLE_NAME_LENGTH} characters`)
        }
        const truncatedServiceName = serviceName.slice(0, serviceName.length - excessCharacters)
        taskRoleName = `OSMigrations-${stage}-${region}-${truncatedServiceName}-task`
    }
    return new Role(scope, `${serviceName}-TaskRole`, {
        assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
        description: `ECS Service Task Role for ${serviceName}`,
        roleName: taskRoleName
    });
}

export function createDefaultECSTaskRole(scope: Construct, serviceName: string, region: string, stage: string): Role {
    const serviceTaskRole = createECSTaskRole(scope, serviceName, region, stage)
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

export function createSnapshotOnAOSRole(scope: Construct, artifactS3Arn: string, migrationConsoleTaskRoleArn: string,
                                        region: string, stage: string, defaultDeployId: string): Role {
    const snapshotRole = new Role(scope, `SnapshotRole`, {
        assumedBy: new ServicePrincipal('es.amazonaws.com'),  // Note that snapshots are not currently possible on AOSS
        description: 'Role that grants OpenSearch Service permissions to access S3 to create snapshots',
        roleName: `OSMigrations-${stage}-${region}-${defaultDeployId}-SnapshotRole`
    });
    snapshotRole.addToPolicy(new PolicyStatement({
        effect: Effect.ALLOW,
        actions: ['s3:ListBucket'],
        resources: [artifactS3Arn],
    }));

    snapshotRole.addToPolicy(new PolicyStatement({
        effect: Effect.ALLOW,
        actions: ['s3:GetObject', 's3:PutObject', 's3:DeleteObject'],
        resources: [`${artifactS3Arn}/*`],
    }));

    // The Migration Console Role needs to be able to pass the snapshot role as well as any other role
    const requestingRole = Role.fromRoleArn(scope, 'RequestingRole', migrationConsoleTaskRoleArn);
    snapshotRole.grantPassRole(requestingRole);
    
    // Grant broader permission to pass any role in the account, enabling support for user-provided snapshot roles at runtime
    requestingRole.addToPrincipalPolicy(new PolicyStatement({
        effect: Effect.ALLOW,
        actions: ['iam:PassRole'],
        resources: [`arn:aws:iam::${Stack.of(scope).account}:role/*`]
    }));

    return snapshotRole
}


export function validateFargateCpuArch(cpuArch?: string): CpuArchitecture {
    const desiredArch = cpuArch ?? process.arch
    const desiredArchUpper = desiredArch.toUpperCase()

    if (desiredArchUpper === "X86_64" || desiredArchUpper === "X64") {
        return CpuArchitecture.X86_64
    } else if (desiredArchUpper === "ARM64") {
        return CpuArchitecture.ARM64
    } else if (cpuArch) {
        throw new Error(`Unknown Fargate cpu architecture provided: ${desiredArch}`)
    }
    else {
        throw new Error(`Unsupported process cpu architecture detected: ${desiredArch}, CDK requires X64 or ARM64 for Docker image compatability`)
    }
}

export function parseRemovalPolicy(optionName: string, policyNameString?: string): RemovalPolicy|undefined {
    const policy = policyNameString ? RemovalPolicy[policyNameString as keyof typeof RemovalPolicy] : undefined
    if (policyNameString && !policy) {
        throw new Error(`Provided '${optionName}' with value '${policyNameString}' does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.RemovalPolicy.html`)
    }
    return policy
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
    KAFKA_BROKERS = 'kafkaBrokers',
    MSK_CLUSTER_ARN = 'mskClusterARN',
    MSK_CLUSTER_NAME = 'mskClusterName',
    OS_ACCESS_SECURITY_GROUP_ID = 'osAccessSecurityGroupId',
    OS_CLUSTER_ENDPOINT = 'osClusterEndpoint',
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


// eslint-disable-next-line @typescript-eslint/no-extraneous-class
export class ClusterNoAuth {}

export class ClusterSigV4Auth {
    region?: string;
    serviceSigningName?: string;
    constructor({region, serviceSigningName: service}: {region: string, serviceSigningName: string}) {
        this.region = region;
        this.serviceSigningName = service;
    }

    toDict() {
        return {
            region: this.region,
            service: this.serviceSigningName
        }
    }
}

export class ClusterBasicAuth {
    constructor(public user_secret_arn: string) {}
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
            return {sigv4: this.sigv4.toDict()};
        }
        return {};
    }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function getBasicClusterAuth(basicAuthObject: Record<any, any>, clusterType: ClusterType, scope: Construct, stage: string, deployId: string): ClusterBasicAuth {
    const { username, password, userSecretArn } = basicAuthObject;

    for (const [key, value] of Object.entries({ username, password, userSecretArn })) {
        if (value !== undefined) {
            if (typeof value !== 'string' || value.trim().length === 0) {
                throw new Error(`Cluster auth field '${key}' must be a non-empty string.`);
            }
        }
    }

    let user_secret_arn = userSecretArn
    if (userSecretArn) {
        if (username || password) {
            throw new Error("Provide only userSecretArn or username/password, not both.");
        }
    } else if (!username || !password) {
        throw new Error("Both username and password must be provided if userSecretArn is not given.");
    }

    user_secret_arn ??= createBasicAuthSecret(username, password, clusterType, scope, stage, deployId).secretArn;

    return new ClusterBasicAuth(user_secret_arn)
}

export function createBasicAuthSecret(username: string, password: string, clusterType: ClusterType, scope: Construct,
                               stage: string, deployId: string): Secret {
    CdkLogger.warn(`Password passed in plain text for ${clusterType} cluster, this is insecure and will leave your password exposed.`)
    return new Secret(scope, `${clusterType}ClusterBasicAuthSecret`, {
        secretName: `${clusterType}-cluster-basic-auth-secret-${stage}-${deployId}`,
        secretObjectValue: {
            username: SecretValue.unsafePlainText(username),
            password: SecretValue.unsafePlainText(password)
        }
    })
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function getSigV4ClusterAuth(sigv4AuthObject: Record<string, any>): ClusterSigV4Auth {
    // Destructure and validate the input object
    const { serviceSigningName, region } = sigv4AuthObject;

    // Create and return the ClusterSigV4Auth object
    return new ClusterSigV4Auth({serviceSigningName, region});
}

// Function to parse and validate auth object
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function parseAuth(json: any, clusterType: ClusterType, scope: Construct, stage: string, deployId: string): ClusterAuth | null {
    if (json.type === 'basic') {
        return new ClusterAuth({basicAuth: getBasicClusterAuth(json, clusterType, scope, stage, deployId)});
    } else if (json.type === 'sigv4' && typeof json.region === 'string' && typeof json.serviceSigningName === 'string') {
        return new ClusterAuth({sigv4: getSigV4ClusterAuth(json)});
    } else if (json.type === 'none') {
        return new ClusterAuth({noAuth: new ClusterNoAuth()});
    }
    return null; // Invalid auth type
}

// Validate a proper url string is provided and return an url string which contains a protocol, host name, and port.
// If a port is not provided, the default protocol port (e.g. 443, 80) will be explicitly added
export function validateAndReturnFormattedHttpURL(urlString: string) {
    // URL will throw error if the urlString is invalid
    const url = new URL(urlString);
    if (url.protocol !== "http:" && url.protocol !== "https:") {
        throw new Error(`Invalid url protocol for endpoint: ${urlString} was expecting 'http' or 'https'`)
    }
    if (url.pathname !== "/") {
        throw new Error(`Provided endpoint: ${urlString} must not contain a path: ${url.pathname}`)
    }
    // URLs that contain the default protocol port (e.g. 443, 80) will not show in the URL toString()
    let formattedUrlString = url.toString()
    if (formattedUrlString.endsWith("/")) {
        formattedUrlString = formattedUrlString.slice(0, -1)
    }
    if (!url.port) {
        if (url.protocol === "http:") {
            formattedUrlString = formattedUrlString.concat(":80")
        }
        else {
            formattedUrlString = formattedUrlString.concat(":443")
        }
    }
    return formattedUrlString
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function parseClusterDefinition(json: any, clusterType: ClusterType, scope: Construct, stage: string, deployId: string): ClusterYaml {
    let endpoint = json.endpoint
    if (!endpoint) {
        throw new Error(`Missing required field in ${clusterType} cluster definition: endpoint`)
    }
    const version = json.version;
    if (clusterType == ClusterType.SOURCE && !version) {
        throw new Error(`Missing required field in ${clusterType} cluster definition: version`)
    }
    endpoint = validateAndReturnFormattedHttpURL(endpoint)
    const allowInsecure = json.allow_insecure;
    const auth = parseAuth(json.auth, clusterType, scope, stage, deployId)
    if (!auth) {
        throw new Error(`Invalid auth type when parsing ${clusterType} cluster definition: ${json.auth.type}`)
    }
    return new ClusterYaml({endpoint, version, allowInsecure, auth})
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function parseSnapshotDefinition(json: any): SnapshotYaml {
    const snapshotName = json.snapshotName
    const snapshotRepoName = json.snapshotRepoName
    const s3Region = json.s3Region
    const s3Uri = json.s3Uri
    if (!snapshotName || !snapshotRepoName || !s3Region || !s3Uri) {
        throw new Error('Missing at least one of the required snapshot fields: snapshotName, snapshotRepoName, s3Region, s3Uri');
    }
    const snapshotYaml = new SnapshotYaml()
    snapshotYaml.snapshot_name = snapshotName
    snapshotYaml.snapshot_repo_name = snapshotRepoName
    snapshotYaml.s3 = {repo_uri: s3Uri, aws_region: s3Region}
    return snapshotYaml
}

export function isStackInGovCloud(stack: Stack): boolean {
        return isRegionGovCloud(stack.region);
}

export function isRegionGovCloud(region: string): boolean {
    return region.startsWith('us-gov-');
}


/**
 * Creates a Local Docker image asset from the specified image name.
 *
 * This allows us to create a private ECR repo for any image allowing us to have a consistent
 * experience across VPCs and regions (e.g. running within VPC in gov-cloud with no internet access)
 *
 * This works by creating a temp Dockerfile with only the FROM with the param imageName and
 * using that Dockerfile with cdk.assets to create a local Docker image asset.
 *
 * @param {string} imageName - The name of the Docker image to save as a tarball and use in CDK.
 * @returns {ContainerImage} - A `ContainerImage` object representing the Docker image asset.
 */
export function makeLocalAssetContainerImage(scope: Construct, imageName: string): ContainerImage {
        const sanitizedImageName = imageName.replaceAll(/[^a-zA-Z0-9-_]/g, '_');
        const tempDir = mkdtempSync(join(tmpdir(), 'docker-build-' + sanitizedImageName));
        const dockerfilePath = join(tempDir, 'Dockerfile');

        let imageHash = null;
        try {
            // Update the image if it is not a local image
            if (!imageName.startsWith('migrations/')) {
                execSync(`docker pull ${imageName}`);
            }
            // Get the actual hash for the image
            const imageId = execSync(`docker inspect --format='{{.Id}}' ${imageName}`).toString().trim();
            if (!imageId) {
                throw new Error(`No RepoDigests found for image: ${imageName}`);
            }
            imageHash = imageId.replaceAll(/[^a-zA-Z0-9-_]/g, '_');
            CdkLogger.info('For image: ' + imageName + ' found imageHash: ' + imageHash);
        } catch (error) {
            CdkLogger.error('Error fetching the actual hash for the image: ' + imageName + ' Error: ' + error);
            throw new Error('Error fetching the image hash for the image: ' + imageName + ' Error: ' + error);
        }

        const dockerfileContent = `
            FROM ${imageName}
        `;

        writeFileSync(dockerfilePath, dockerfileContent);
        const assetName = `${sanitizedImageName.charAt(0).toUpperCase() + sanitizedImageName.slice(1)}ImageAsset`;
        const asset = new DockerImageAsset(scope, assetName, {
            directory: tempDir,
            // add the tag to the hash so that the asset is invalidated when the tag changes
            extraHash: imageHash,
            assetName: assetName,
        });
        return ContainerImage.fromDockerImageAsset(asset);
    }