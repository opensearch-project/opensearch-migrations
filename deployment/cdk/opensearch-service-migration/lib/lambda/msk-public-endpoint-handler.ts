import {Context} from 'aws-lambda';
import {DescribeClusterV2Command, UpdateConnectivityCommand, GetBootstrapBrokersCommand, KafkaClient} from "@aws-sdk/client-kafka";
import {InvokeCommand, LambdaClient} from "@aws-sdk/client-lambda"

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
                console.log("Update connectivity error: " + JSON.stringify(error))
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
                console.log("Describe cluster error: " + JSON.stringify(error))
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
                console.log("Get brokers error: " + JSON.stringify(error))
            }
        );
    } catch (e) {
        console.error(e);
    }
    return result
}

async function invokeNextLambda(payload: any, functionName: string) {
    const lambdaClient = new LambdaClient({})
    const params = {
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
                data.$metadata.httpStatusCode

            },
            (error) => {
                console.log("Invoke lambda error: " + JSON.stringify(error))
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
        maxAttempts = 3
    }
    if (!process.env.MSK_ARN) {
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
            console.log("Unable to conclude update has completed after max attempts... Stopping now")
            return
        }
        attempt = event.Attempt + 1
    }

    const payloadData = {
        Attempt: attempt,
        CallbackUrl: callback
    }
    while(context.getRemainingTimeInMillis() > 25000) {
        const clusterResponse = await describeClusterV2()
        const state = clusterResponse.ClusterInfo.State
        console.log("Current cluster state is: " + state)
        if (state == "ACTIVE") {
            console.log("MSK cluster is now 'Active', finishing Lambda")
            const brokerResponse = await getBootstrapBrokers()
            const responseBody = {
                Status: "SUCCESS",
                Reason: "Cluster connectivity update has successfully finished",
                // Since our wait condition only needs one occurrence this value can be any static value
                UniqueId: "updateConnectivityID",
                Data: `export MIGRATION_KAFKA_BROKER_ENDPOINTS=${brokerResponse.BootstrapBrokerStringPublicSaslIam}`
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