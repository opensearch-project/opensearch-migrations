// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import {Stack, StackProps} from "aws-cdk-lib";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, ContainerImage, FargateService, FargateTaskDefinition, LogDrivers} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {join} from "path";
import { readFileSync } from "fs"

export interface historicalCaptureStackProps extends StackProps {
    readonly vpc: IVpc,
    readonly logstashConfigFilePath: string,
    readonly sourceEndpoint?: string,
    readonly targetEndpoint: string
}


export class HistorialCaptureStack extends Stack {

    constructor(scope: Construct, id: string, props: historicalCaptureStackProps) {
        super(scope, id, props);

        const ecsCluster = new Cluster(this, "ecsHistoricalCaptureCluster", {
            vpc: props.vpc
        });

        const historicalCaptureFargateTask = new FargateTaskDefinition(this, "historicalCaptureFargateTask", {
            memoryLimitMiB: 2048,
            cpu: 512
        });

        let logstashConfigData: string = readFileSync(props.logstashConfigFilePath, 'utf8');
        if (props.sourceEndpoint) {
            logstashConfigData = logstashConfigData.replace("<SOURCE_CLUSTER_HOST>", props.sourceEndpoint)
        }
        logstashConfigData = logstashConfigData.replace("<TARGET_CLUSTER_HOST>", props.targetEndpoint + ":80")
        // Temporary measure to allow multi-line env variable
        logstashConfigData = logstashConfigData.replace(/(\n)/g, "PUT_LINE")
        console.log(logstashConfigData)
        // Create Historical Capture Container
        const historicalCaptureImage = new DockerImageAsset(this, "historicalCaptureImage", {
            directory: join(__dirname, "..", "docker/logstash-setup")
        });

        const historicalCaptureContainer = historicalCaptureFargateTask.addContainer("historicalCaptureContainer", {
            image: ContainerImage.fromDockerImageAsset(historicalCaptureImage),
            // Add in region and stage
            containerName: "logstash",
            environment: {"LOGSTASH_CONFIG": '' + logstashConfigData},
            logging: LogDrivers.awsLogs({ streamPrefix: 'logstash-lg', logRetention: 30 })
        });

        // Create Fargate Service
        const historicalCaptureFargateService = new FargateService(this, "historicalCaptureFargateService", {
            cluster: ecsCluster,
            taskDefinition: historicalCaptureFargateTask,
            desiredCount: 1
        });

    }
}