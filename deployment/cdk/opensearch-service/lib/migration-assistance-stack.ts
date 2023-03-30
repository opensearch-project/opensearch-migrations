// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import {Stack, StackProps} from "aws-cdk-lib";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, Compatibility, ContainerImage, FargateTaskDefinition, TaskDefinition} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {join} from "path";
import {
    AnyPrincipal,
    Effect,
    ManagedPolicy,
    PolicyDocument,
    PolicyStatement,
    Role,
    ServicePrincipal
} from "aws-cdk-lib/aws-iam";

export interface migrationStackProps extends StackProps {
    readonly vpc?: IVpc,
    readonly sourceCWLogStreamARN: string,
    readonly targetEndpoint: string
}


export class MigrationAssistanceStack extends Stack {

    constructor(scope: Construct, id: string, props: migrationStackProps) {
        super(scope, id, props);

        // Create IAM Role for Fargate Task to read from CW log group
        const cwAccessPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            // At the LG level these permissions may be necessary "logs:DescribeLogGroups", "logs:DescribeLogStreams"
            actions: ["logs:FilterLogEvents", "logs:GetLogEvents"],
            resources: [props.sourceCWLogStreamARN]
        })
        const cwAccessDoc = new PolicyDocument({
            statements: [cwAccessPolicy]
        })
        const cwRole = new Role(this, 'CWReadAccessRole', {
            assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
            description: 'Allow Fargate container to access CW log group',
            inlinePolicies: {
                ReadCWLogGroup: cwAccessDoc,
            },
        });

        const ecsCluster = new Cluster(this, "ecsMigrationCluster", {
            vpc: props.vpc
        });

        const migrationFargateTask = new FargateTaskDefinition(this, "migrationFargateTask", {
            memoryLimitMiB: 2048,
            cpu: 512,
            taskRole: cwRole
        });

        // Create CW Puller Container
        const cwPullerImage = new DockerImageAsset(this, "CWPullerImage", {
            directory: join(__dirname, "..", "docker-cw-puller")
        });
        const cwPullerContainer = migrationFargateTask.addContainer("CWPullerContainer", {
            image: ContainerImage.fromDockerImageAsset(cwPullerImage),
            // Add in region and stage
            containerName: "cw-puller",
            environment: {}
        });

        // Create Traffic Replayer Container
        const trafficReplayerImage = new DockerImageAsset(this, "TrafficReplayerImage", {
            directory: join(__dirname, "..", "docker-traffic-replayer")
        });
        const trafficReplayerContainer = migrationFargateTask.addContainer("TrafficReplayerContainer", {
            image: ContainerImage.fromDockerImageAsset(trafficReplayerImage),
            // Add in region and stage
            containerName: "traffic-replayer",
            environment: {}
        });

        // Create EC2 instance for analysis of cluster in VPC
    }
}