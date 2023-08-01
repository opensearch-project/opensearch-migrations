import {StackPropsExt} from "./stack-composer";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {CfnOutput, CfnWaitCondition, CfnWaitConditionHandle, CustomResource, Duration, Stack} from "aws-cdk-lib";
import {Construct} from "constructs";
import {Effect, ManagedPolicy, PolicyDocument, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {NodejsFunction} from "aws-cdk-lib/aws-lambda-nodejs";
import {Runtime} from "aws-cdk-lib/aws-lambda";
import {AwsCustomResource, AwsCustomResourcePolicy, PhysicalResourceId, Provider} from "aws-cdk-lib/custom-resources";
import * as path from "path";

export interface mskUtilityStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly mskARN: string,
    readonly mskEnablePublicEndpoints?: boolean
}


export class MSKUtilityStack extends Stack {

    constructor(scope: Construct, id: string, props: mskUtilityStackProps) {
        super(scope, id, props);

        let brokerEndpointsOutput
        if (props.mskEnablePublicEndpoints) {
            const lambdaInvokeStatement = new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ["lambda:InvokeFunction"],
                resources: ["*"]
            })
            // Updating connectivity for an MSK cluster requires some VPC permissions
            // (https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazonmanagedstreamingforapachekafka.html#amazonmanagedstreamingforapachekafka-cluster)
            const describeVPCStatement = new PolicyStatement( {
                effect: Effect.ALLOW,
                actions: ["ec2:DescribeSubnets",
                    "ec2:DescribeRouteTables"],
                resources: ["*"]
            })
            const mskUpdateConnectivityStatement = new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ["kafka:UpdateConnectivity",
                    "kafka:DescribeClusterV2",
                    "kafka:GetBootstrapBrokers"],
                resources: [props.mskARN]
            })
            const lambdaExecDocument = new PolicyDocument({
                statements: [lambdaInvokeStatement, describeVPCStatement, mskUpdateConnectivityStatement]
            })

            const lambdaExecRole = new Role(this, 'mskAccessLambda', {
                assumedBy: new ServicePrincipal('lambda.amazonaws.com'),
                description: 'Allow lambda to access MSK and invoke itself',
                inlinePolicies: {
                    mskAccessDoc: lambdaExecDocument,
                },
                // Required policies that the default Lambda exec role would add
                managedPolicies: [
                    ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                    ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ]
            });

            const mskPublicLambda = new NodejsFunction(this, 'mskPublicLambdaFunction', {
                runtime: Runtime.NODEJS_18_X,
                vpc: props.vpc,
                handler: 'handler',
                timeout: Duration.minutes(15),
                entry: path.join(__dirname, 'lambda/msk-public-endpoint-handler.ts'),
                role: lambdaExecRole,
                environment: {MSK_ARN: props.mskARN, MAX_ATTEMPTS: "4"}
            });

            const customResourceProvider = new Provider(this, 'customResourceProvider', {
                onEventHandler: mskPublicLambda,
            });

            // WaitConditions will by default only run on create, the workaround here is to generate a unique id on each
            // execution so that a new wait condition is created
            const currentTime = Date.now().toString()
            const wcHandle = new CfnWaitConditionHandle(this, 'WaitConditionHandle'.concat(currentTime));

            const customResource = new CustomResource(this, "mskAccessLambdaCustomResource", {
                serviceToken: customResourceProvider.serviceToken,
                properties: {
                    CallbackUrl: wcHandle.ref
                }
            })

            // Note: AWS::CloudFormation::WaitCondition resource type does not support updates.
            const waitCondition = new CfnWaitCondition(this, 'WaitCondition'.concat(currentTime), {
                count: 1,
                timeout: Duration.minutes(45).toSeconds().toString(),
                handle: wcHandle.ref
            })
            waitCondition.node.addDependency(customResource);
            brokerEndpointsOutput = waitCondition.attrData.toString()
        }
        else {
            const mskGetBrokersCustomResource = getBrokersCustomResource(this, props.vpc, props.mskARN)
            brokerEndpointsOutput = `export MIGRATION_KAFKA_BROKER_ENDPOINTS=${mskGetBrokersCustomResource.getResponseField("BootstrapBrokerStringSaslIam")}`
            //brokerEndpointsOutput = mskGetBrokersCustomResource.getResponseField("BootstrapBrokerStringPublicSaslIam")
        }

        const cfnOutput = new CfnOutput(this, 'CopilotBrokerEndpointsExport', {
            value: brokerEndpointsOutput,
            description: 'Exported MSK broker endpoint values created by CDK that are needed by Copilot container deployments',
        });
    }
}

export function getBrokersCustomResource(scope: Construct, vpc: IVpc, clusterArn: string): AwsCustomResource {
    const getBrokersCR = new AwsCustomResource(scope, 'migrationMSKGetBrokersCR', {
        onCreate: {
            service: 'Kafka',
            action: 'getBootstrapBrokers',
            parameters: {
                ClusterArn: clusterArn,
            },
            physicalResourceId: PhysicalResourceId.of(Date.now().toString())
        },
        onUpdate: {
            service: 'Kafka',
            action: 'getBootstrapBrokers',
            parameters: {
                ClusterArn: clusterArn,
            },
            physicalResourceId: PhysicalResourceId.of(Date.now().toString())
        },
        policy: AwsCustomResourcePolicy.fromSdkCalls({resources: [clusterArn]}),
        vpc: vpc
    })
    return getBrokersCR
}