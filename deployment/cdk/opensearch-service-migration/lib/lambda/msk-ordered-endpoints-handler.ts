import {Context} from 'aws-lambda';
import {GetBootstrapBrokersCommand, KafkaClient} from "@aws-sdk/client-kafka";

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

export const handler = async (event: any, context: Context) => {
    console.log('Lambda is invoked with event:' + JSON.stringify(event));
    console.log('Lambda is invoked with context:' + JSON.stringify(context));

    if (!process.env.MSK_ARN) {
        throw Error(`Missing at least one required environment variable [MSK_ARN: ${process.env.MSK_ARN}]`)
    }

    const brokerResponse = await getBootstrapBrokers()
    if (!brokerResponse) {
        throw Error('Unable to retrieve MSK Bootstrap Brokers from getBootstrapBrokers AWS call, terminating now')
    }
    const brokers = brokerResponse.BootstrapBrokerStringSaslIam
    const orderedBrokers = brokers.split(",").sort().join(",")
    console.log(`Retrieved brokers: ${orderedBrokers}`)
    return {
        UniqueId: "getMSKBrokers",
        Data: {
            BROKER_ENDPOINTS: orderedBrokers
        }
    }

};