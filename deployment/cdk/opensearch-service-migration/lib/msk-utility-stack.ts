import {StackPropsExt} from "./stack-composer";
import {IVpc} from "aws-cdk-lib/aws-ec2";
import {CfnWaitCondition, CfnWaitConditionHandle, CustomResource, Duration, Stack} from "aws-cdk-lib";
import {Construct} from "constructs";
import {Effect, ManagedPolicy, PolicyDocument, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {NodejsFunction} from "aws-cdk-lib/aws-lambda-nodejs";
import {Runtime} from "aws-cdk-lib/aws-lambda";
import {Provider} from "aws-cdk-lib/custom-resources";
import * as path from "path";
import {StringParameter} from "aws-cdk-lib/aws-ssm";

export interface MskUtilityStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly mskEnablePublicEndpoints?: boolean
}

/**
 * This stack exists to provide additional needed functionality to the L1 MSK Construct. This functionality includes
 * enabling public endpoints on a created MSK cluster. As well as retrieving public and private broker endpoints in
 * a consistent ORDERED fashion.
 */
export class MSKUtilityStack extends Stack {

    constructor(scope: Construct, id: string, props: MskUtilityStackProps) {
        super(scope, id, props);

        const mskARN = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskClusterARN`)
        let brokerEndpoints
        // If the public endpoints setting is enabled we will launch a Lambda custom resource to enable public endpoints and then wait for these
        // endpoints to become created before we return the endpoints and stop the process
        if (props.mskEnablePublicEndpoints) {
            const lambdaInvokeStatement = new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ["lambda:InvokeFunction"],
                resources: [`arn:aws:lambda:${props.env?.region}:${props.env?.account}:function:OSMigrations*`]
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
                resources: [mskARN]
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
                environment: {MSK_ARN: mskARN, MAX_ATTEMPTS: "4"}
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
            brokerEndpoints = waitCondition.attrData.toString()
        }
        // If public endpoints are not enabled we will launch a simple Lambda custom resource to retrieve the private broker endpoints
        else {
            const mskUpdateConnectivityStatement = new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ["kafka:GetBootstrapBrokers"],
                resources: [mskARN]
            })
            const lambdaExecDocument = new PolicyDocument({
                statements: [mskUpdateConnectivityStatement]
            })

            const lambdaExecRole = new Role(this, 'mskAccessLambda', {
                assumedBy: new ServicePrincipal('lambda.amazonaws.com'),
                description: 'Allow lambda to access MSK to retrieve brokers',
                inlinePolicies: {
                    mskAccessDoc: lambdaExecDocument,
                },
                // Required policies that the default Lambda exec role would add
                managedPolicies: [
                    ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                    ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ]
            });

            const mskPublicLambda = new NodejsFunction(this, 'mskGetBrokersLambdaFunction', {
                runtime: Runtime.NODEJS_18_X,
                vpc: props.vpc,
                handler: 'handler',
                timeout: Duration.minutes(3),
                entry: path.join(__dirname, 'lambda/msk-ordered-endpoints-handler.ts'),
                role: lambdaExecRole,
                environment: {MSK_ARN: mskARN}
            });

            const customResourceProvider = new Provider(this, 'customResourceProvider', {
                onEventHandler: mskPublicLambda,
            });

            const customResource = new CustomResource(this, "mskGetBrokersCustomResource", {
                serviceToken: customResourceProvider.serviceToken
            })
            // Access BROKER_ENDPOINTS from the Lambda return value
            brokerEndpoints = customResource.getAttString("BROKER_ENDPOINTS")
        }
        new StringParameter(this, 'SSMParameterKafkaBrokers', {
            description: 'OpenSearch Migration Parameter for Kafka brokers',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`,
            stringValue: brokerEndpoints
        });
    }
}