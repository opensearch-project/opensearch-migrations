import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {Construct} from "constructs";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";

export function createOpenSearchIAMAccessPolicy(region: string, accountId: string): PolicyStatement {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [`arn:aws:es:${region}:${accountId}:domain/*`],
        actions: [
            "es:ESHttp*"
        ]
    })
}

export function createOpenSearchServerlessIAMAccessPolicy(region: string, accountId: string): PolicyStatement {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [`arn:aws:aoss:${region}:${accountId}:collection/*`],
        actions: [
            "aoss:APIAccessAll"
        ]
    })
}

export function createMSKConsumerIAMPolicies(scope: Construct, region: string, accountId: string, stage: string, deployId: string): PolicyStatement[] {
    const mskClusterARN = StringParameter.valueForStringParameter(scope, `/migration/${stage}/${deployId}/mskClusterARN`);
    const mskClusterName = StringParameter.valueForStringParameter(scope, `/migration/${stage}/${deployId}/mskClusterName`);
    const mskClusterConnectPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterARN],
        actions: [
            "kafka-cluster:Connect"
        ]
    })
    const mskClusterAllTopicArn = `arn:aws:kafka:${region}:${accountId}:topic/${mskClusterName}/*`
    const mskTopicConsumerPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterAllTopicArn],
        actions: [
            "kafka-cluster:DescribeTopic",
            "kafka-cluster:ReadData"
        ]
    })
    const mskClusterAllGroupArn = `arn:aws:kafka:${region}:${accountId}:group/${mskClusterName}/*`
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

export function createMSKProducerIAMPolicies(scope: Construct, region: string, accountId: string, stage: string, deployId: string): PolicyStatement[] {
    const mskClusterARN = StringParameter.valueForStringParameter(scope, `/migration/${stage}/${deployId}/mskClusterARN`);
    const mskClusterName = StringParameter.valueForStringParameter(scope, `/migration/${stage}/${deployId}/mskClusterName`);
    const mskClusterConnectPolicy = new PolicyStatement({
        effect: Effect.ALLOW,
        resources: [mskClusterARN],
        actions: [
            "kafka-cluster:Connect"
        ]
    })
    const mskClusterAllTopicArn = `arn:aws:kafka:${region}:${accountId}:topic/${mskClusterName}/*`
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

export function validateFargateCpuArch(cpuArch: string): CpuArchitecture {
    if (cpuArch.toUpperCase() === "X86_64") {
        return CpuArchitecture.X86_64
    }
    else if (cpuArch.toUpperCase() === "ARM64") {
        return CpuArchitecture.ARM64
    }
    else {
        throw new Error(`Unknown Fargate cpu architecture provided: ${cpuArch}`)
    }
}