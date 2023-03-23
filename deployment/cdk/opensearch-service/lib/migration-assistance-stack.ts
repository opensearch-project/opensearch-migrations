// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import {Stack, StackProps} from "aws-cdk-lib";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, Compatibility, ContainerImage, TaskDefinition} from "aws-cdk-lib/aws-ecs";
import {ApplicationLoadBalancedFargateService} from "aws-cdk-lib/aws-ecs-patterns";
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
    readonly sourceCWLogGroupARN: string,
    readonly targetEndpoint: string
}


export class MigrationAssistanceStack extends Stack {

    constructor(scope: Construct, id: string, props: migrationStackProps) {
        super(scope, id, props);

        const image = new DockerImageAsset(this, "ClusterQueryImage", {
            directory: join(__dirname, "..", "python-docker")
        });

        const ecsCluster = new Cluster(this, "ecsMigrationCluster", {
            vpc: props.vpc
        });

        // Create IAM Role for Fargate Container to read from CW log group
        const cwAccessPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["logs:FilterLogEvents", "logs:GetLogEvents", "logs:DescribeLogGroups", 'logs:DescribeLogStreams'],
            // Clean up
            resources: [props.sourceCWLogGroupARN + ":*"]
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
            // managedPolicies: [
            //     ManagedPolicy.fromAwsManagedPolicyName(
            //         'AmazonAPIGatewayInvokeFullAccess',
            //     ),
            // ],
        });


        // This is currently a blocker for Domains that wish to be in a single AZ (i.e. single node clusters) as the
        // ALB here enforces that the VPC contain at least two subnets in different AZs

        // Create a load-balanced Fargate service
        new ApplicationLoadBalancedFargateService(this, "fargateMigrationService", {
            cluster: ecsCluster,
            cpu: 512, // Default is 256
            desiredCount: 1, // Default is 1
            taskImageOptions: {
                taskRole: cwRole,
                image: ContainerImage.fromDockerImageAsset(image),
                environment: {"MIGRATION_ENDPOINT": "https://" + props.targetEndpoint, "SOURCE_CW_LG_ARN": props.sourceCWLogGroupARN}
                //containerPort: 8080,
            },
            //taskImageOptions: { image: ContainerImage.fromRegistry("amazon/amazon-ecs-sample") },
            memoryLimitMiB: 2048, // Default is 512
            publicLoadBalancer: false // Default is true
        });

        // Create EC2 instance for analysis of cluster in VPC
    }
}