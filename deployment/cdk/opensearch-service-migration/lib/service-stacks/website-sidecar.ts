import {
    PortMapping,
    Protocol,
    LogDrivers,
    AwsLogDriverMode,
    TaskDefinition
} from "aws-cdk-lib/aws-ecs";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {makeLocalAssetContainerImage} from "../common-utilities";
import {RemovalPolicy} from "aws-cdk-lib";

// eslint-disable-next-line @typescript-eslint/no-extraneous-class
export class WebsiteSidecar {

    static addWebsiteContainer(taskDefinition: TaskDefinition, logGroupPrefix: string) {
        const httpPortMapping: PortMapping = {
            name: "http",
            hostPort: 80,
            containerPort: 80,
            protocol: Protocol.TCP
        }

        const serviceLogGroup = new LogGroup(taskDefinition.stack, 'WebsiteLogGroup',  {
            retention: RetentionDays.ONE_MONTH,
            removalPolicy: RemovalPolicy.DESTROY,
            logGroupName: `${logGroupPrefix}/website`
        });
        const websiteContainer = taskDefinition.addContainer("WebsiteContainer", {
            image: makeLocalAssetContainerImage(taskDefinition.stack, "migrations/website:latest"),
            containerName: "website",
            portMappings: [httpPortMapping],
            logging: LogDrivers.awsLogs({
                streamPrefix: "website-logs",
                logGroup: serviceLogGroup,
                mode: AwsLogDriverMode.BLOCKING,
            }),
            essential: false,
        });

        return websiteContainer;
    }
}
