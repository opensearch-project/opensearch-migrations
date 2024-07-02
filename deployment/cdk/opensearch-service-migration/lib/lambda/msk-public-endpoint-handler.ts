import {Context} from 'aws-lambda';
import {DescribeClusterV2Command, UpdateConnectivityCommand, GetBootstrapBrokersCommand, KafkaClient} from "@aws-sdk/client-kafka";
import {InvokeCommand, InvokeCommandInput, LambdaClient} from "@aws-sdk/client-lambda"

/**
 *
 * NOTE: This is a temporary piece to allow enabling MSK public endpoint upon creation of MSK in CDK. It will regularly check the
 * MSK cluster until the update is complete by recursively calling itself (up to a set number of times) until it has detected the
 * cluster to be ACTIVE, which is necessary for updates lasting over 15 minutes, and then trigger a CFN wait condition that
 * the process is complete. This is not best practice and should be replaced with something such as AWS Step Functions in a future update.
 *
 */

function delay(ms: number) {
    return new Promise( resolve => setTimeout(resolve, ms) );
}

async function enablePublicEndpoint(mskVersion: string) {
    const mskClient = new KafkaClient({})
    const params = {
        ClusterArn: process.env.MSK_ARN,
        ConnectivityInfo: {
            PublicAccess: {
                Type: 'SERVICE_PROVIDED_EIPS'
            }
        },
        CurrentVersion: mskVersion
    }
    const command = new UpdateConnectivityCommand(params)
    try {
        console.log("Starting update connectivity call")
        await mskClient.send(command).then(
            (data) => {
                console.log("Update connectivity response: " + JSON.stringify(data))

            },
            (error) => {
                console.error("Update connectivity error: " + JSON.stringify(error))
            }
        );
    } catch (e) {
        console.error(e);
    }
}

async function describeClusterV2(): Promise<any> {
    const mskClient = new KafkaClient({})
    const params = {
        ClusterArn: process.env.MSK_ARN
    }
    const command = new DescribeClusterV2Command(params)
    let result
    try {
        console.log("Starting describe cluster call")
        await mskClient.send(command).then(
            (data) => {
                console.log("Describe cluster response: " + JSON.stringify(data))
                result = data
            },
            (error) => {
                console.error("Describe cluster error: " + JSON.stringify(error))
            }
        );
    } catch (e) {
        console.error(e);
    }
    return result
}

async function getBootstrapBrokers(): Promise<any> {
    const mskClient = new KafkaClient({})
    const params = {
        ClusterArn: process.env.MSK_ARN
    }
    const command = new GetBootstrapBrokersCommand(params)
    let result
    try {
        console.log("Starting get brokers call")
        await mskClient.send(command).then(
            (data) => {
                console.log("Get brokers response: " + JSON.stringify(data))
                result = data
            },
            (error) => {
                console.error("Get brokers error: " + JSON.stringify(error))
            }
        );
    } catch (e) {
        console.error(e);
    }
    return result
}

async function invokeNextLambda(payload: any, functionName: string) {
    const lambdaClient = new LambdaClient({})
    const params : InvokeCommandInput = {
        FunctionName: functionName,
        Payload: payload,
        // Perform async request which does not wait on response
        InvocationType: "Event"
    }
    const command = new InvokeCommand(params)
    try {
        console.log("Starting invoke lambda call")
        await lambdaClient.send(command).then(
            (data) => {
                console.log("Invoke lambda response: " + JSON.stringify(data))
            },
            (error) => {
                console.error("Invoke lambda error: " + JSON.stringify(error))
            }
        );
    } catch (e) {
        console.error(e);
    }
}

export const handler = async (event: any, context: Context): Promise<void> => {
    console.log('Lambda is invoked with event:' + JSON.stringify(event));
    console.log('Lambda is invoked with context:' + JSON.stringify(context));

    let maxAttempts = process.env.MAX_ATTEMPTS ? parseInt(process.env.MAX_ATTEMPTS) : undefined
    if (!maxAttempts) {
        console.log('Max attempts not provided by environment or unable to be parsed, defaulting to 3 attempts')
        maxAttempts = 4
    }
    if (!process.env.MSK_ARN) {
        const failedInputRequirementResponse = {
            Status: "FAILURE",
            Reason: "Unable to process connectivity update as a required env variable is missing [MSK_ARN]",
            // Since our wait condition only needs one occurrence this value can be any static value
            UniqueId: "updateConnectivityID",
            Data: "Update failed: Unable to process connectivity update as a required env variable is missing [MSK_ARN]"
        }
        const waitCondCall = event.Attempt == undefined ? event.ResourceProperties.CallbackUrl : event.CallbackUrl
        // @ts-ignore
        await fetch(waitCondCall, {method: 'PUT', body: JSON.stringify(failedInputRequirementResponse)});
        throw Error(`Missing at least one required environment variable [MSK_ARN: ${process.env.MSK_ARN}]`)
    }

    let attempt = 1;
    let callback = "";
    // Initial pass
    if (event.Attempt == undefined) {
        console.log("Starting initial attempt")
        callback = event.ResourceProperties.CallbackUrl
        attempt = attempt + 1
        const clusterDetailsResponse = await describeClusterV2()
        await enablePublicEndpoint(clusterDetailsResponse.ClusterInfo.CurrentVersion)
        await delay(5000);
    }
    // Additional extended time passes
    else {
        console.log(`Starting attempt no. ${event.Attempt}`)
        callback = event.CallbackUrl
        if (event.Attempt > maxAttempts) {
            console.log("Unable to conclude connectivity update has completed after max attempts... Alerting failure and stopping now")
            const failedTimeRequirementResponse = {
                Status: "FAILURE",
                Reason: "Unable to conclude update has completed after max attempts",
                // Since our wait condition only needs one occurrence this value can be any static value
                UniqueId: "updateConnectivityID",
                Data: "Update failed: Unable to conclude connectivity update has completed after max attempts"
            }
            // @ts-ignore
            await fetch(callback, {method: 'PUT', body: JSON.stringify(failedTimeRequirementResponse)});
            return
        }
        attempt = event.Attempt + 1
    }

    const payloadData = {
        Attempt: attempt,
        CallbackUrl: callback
    }
    // Continue polling until there is 5 minutes or less remaining in Lambda
    while(context.getRemainingTimeInMillis() > 300000) {
        const clusterResponse = await describeClusterV2()
        const state = clusterResponse.ClusterInfo.State
        console.log("Current cluster state is: " + state)
        if (state == "ACTIVE") {
            console.log("MSK cluster is now 'Active', finishing Lambda")
            const brokerResponse = await getBootstrapBrokers()
            const brokers = brokerResponse.BootstrapBrokerStringPublicSaslIam
            const orderedBrokers = brokers.split(",").sort().join(",")
            const responseBody = {
                Status: "SUCCESS",
                Reason: "Cluster connectivity update has successfully finished",
                // Since our wait condition only needs one occurrence this value can be any static value
                UniqueId: "updateConnectivityID",
                Data: orderedBrokers
            }
            // @ts-ignore
            const waitConditionResponse = await fetch(payloadData.CallbackUrl, {method: 'PUT', body: JSON.stringify(responseBody)});
            console.log("Wait condition completion response: " + JSON.stringify(waitConditionResponse))
            return
        }
        await delay(20000);
    }
    await invokeNextLambda(JSON.stringify(payloadData), context.functionName)
};
