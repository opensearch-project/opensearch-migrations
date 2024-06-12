import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {Construct} from "constructs";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {RemovalPolicy} from "aws-cdk-lib";
import { IApplicationLoadBalancer } from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { ICertificate } from "aws-cdk-lib/aws-certificatemanager";
import { MigrationServiceCore, SSMParameter } from "./service-stacks/migration-service-core";

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
    const mskClusterARN = MigrationServiceCore.getStringParameter(scope, SSMParameter.MSK_CLUSTER_ARN, { stage, defaultDeployId: deployId });
    const mskClusterName = MigrationServiceCore.getStringParameter(scope, SSMParameter.MSK_CLUSTER_NAME, { stage, defaultDeployId: deployId });
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
    const mskClusterARN = MigrationServiceCore.getStringParameter(scope, SSMParameter.MSK_CLUSTER_ARN, { stage, defaultDeployId: deployId });
    const mskClusterName = MigrationServiceCore.getStringParameter(scope, SSMParameter.MSK_CLUSTER_NAME, { stage, defaultDeployId: deployId });
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