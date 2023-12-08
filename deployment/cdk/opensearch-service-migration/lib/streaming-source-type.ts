export enum StreamingSourceType {
    AWS_MSK,
    KAFKA_ECS
}

export function determineStreamingSourceType(kafkaBrokerServiceEnabled: boolean): StreamingSourceType {
    return kafkaBrokerServiceEnabled ? StreamingSourceType.KAFKA_ECS : StreamingSourceType.AWS_MSK
}