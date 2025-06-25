export enum StreamingSourceType {
    AWS_MSK = 'AWS_MSK',
    KAFKA_ECS = 'KAFKA_ECS',
    DISABLED = 'DISABLED'
}

export function determineStreamingSourceType(
    captureProxyServiceEnabled: boolean,
    trafficReplayerServiceEnabled: boolean,
    kafkaBrokerServiceEnabled: boolean
): StreamingSourceType {
    if (kafkaBrokerServiceEnabled) {
        return StreamingSourceType.KAFKA_ECS;
    }
    if (captureProxyServiceEnabled || trafficReplayerServiceEnabled) {
        return StreamingSourceType.AWS_MSK;
    }
    return StreamingSourceType.DISABLED;
}