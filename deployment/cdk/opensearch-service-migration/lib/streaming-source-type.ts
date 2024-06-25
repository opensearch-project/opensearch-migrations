export enum StreamingSourceType {
    AWS_MSK = 'AWS_MSK',
    KAFKA_ECS = 'KAFKA_ECS',
    DISABLED = 'DISABLED'
}

export function determineStreamingSourceType(kafkaBrokerServiceEnabled: boolean): StreamingSourceType {
    return kafkaBrokerServiceEnabled ? StreamingSourceType.KAFKA_ECS : StreamingSourceType.AWS_MSK
}