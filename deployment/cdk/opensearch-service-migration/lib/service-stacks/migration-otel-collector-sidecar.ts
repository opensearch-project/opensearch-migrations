import {
    PortMapping,
    Protocol,
    ContainerImage,
    LogDrivers,
    AwsLogDriverMode,
    TaskDefinition
} from "aws-cdk-lib/aws-ecs";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {createAwsDistroForOtelPushInstrumentationPolicy} from "../common-utilities";
import {Duration, RemovalPolicy} from "aws-cdk-lib";
import { DockerImageAsset } from "aws-cdk-lib/aws-ecr-assets";
import { join } from "path";

export class OtelCollectorSidecar {
    public static OTEL_CONTAINER_PORT = 4317;
    public static OTEL_CONTAINER_HEALTHCHECK_PORT = 13133;


    static addOtelCollectorContainer(taskDefinition: TaskDefinition, logGroupPrefix: string) {
        const otelCollectorPort: PortMapping = {
            name: "otel-collector-connect",
            hostPort: this.OTEL_CONTAINER_PORT,
            containerPort: this.OTEL_CONTAINER_PORT,
            protocol: Protocol.TCP
        }

        const otelCollectorHealthcheckPort: PortMapping = {
            name: "otel-collector-healthcheck",
            hostPort: this.OTEL_CONTAINER_HEALTHCHECK_PORT,
            containerPort: this.OTEL_CONTAINER_HEALTHCHECK_PORT,
            protocol: Protocol.TCP
        }

        const serviceLogGroup = new LogGroup(taskDefinition.stack, 'OtelCollectorLogGroup',  {
            retention: RetentionDays.ONE_MONTH,
            removalPolicy: RemovalPolicy.DESTROY,
            logGroupName: `${logGroupPrefix}/otel-collector`
        });
        const otelCollectorContainer = taskDefinition.addContainer("OtelCollectorContainer", {
            image: ContainerImage.fromDockerImageAsset(new DockerImageAsset(taskDefinition.stack, "OtelCollectorImage", {
                directory: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/otelCollector")
            })),
            containerName: "otel-collector",
            command: ["--config=/etc/otel-config-aws.yaml"],
            portMappings: [otelCollectorPort, otelCollectorHealthcheckPort],
            environment: {
                "OTEL_CONFIG": "/etc/otel/config.yaml"
            },
            logging: LogDrivers.awsLogs({
                streamPrefix: "otel-collector-logs",
                logGroup: serviceLogGroup,
                mode: AwsLogDriverMode.BLOCKING,
            }),
            essential: true,
            healthCheck: {
                command: ["CMD", "/healthcheck"],
                interval: Duration.seconds(5),
                retries: 2,
                timeout: Duration.seconds(3),
                startPeriod: Duration.seconds(5),
            }
        });
        taskDefinition.addToTaskRolePolicy(createAwsDistroForOtelPushInstrumentationPolicy());

        return otelCollectorContainer;
    }
}
