import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {Construct} from "constructs";

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