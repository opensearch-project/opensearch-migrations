#!/bin/bash

# A note on the implementation method:
# This would be better implemented as a script using `boto` in python, or using
# the `--cli-input-json` option. However, both of those seem to have a bug that prevents dimensions
# and units from being provided.
# There also seems to be a bug in this CLI interface that prevents multiple dimensions from being provided,
# so for the time being all metrics are provided with 0 or 1 dimensions, and any additional ones need to be
# added in the console UI.

CAPUTRE_PROXY_LOG_GROUP="/copilot/migration-copilot-dev-capture-proxy-es"
TRAFFIC_REPLAYER_LOG_GROUP="/copilot/migration-copilot-dev-traffic-replayer-default"
# TODO: Both of the values above may change. The capture proxy could be running as a standalone and
# therefore not have `-es` as part of the name. If multiple traffic replayers are deployed,
# only the first would use `-default`. For now, the path to resolving this is for the user to
# edit this file and change the values. Down the line, we should incorporate this with `devDeploy`
# to automatically set these values. Additionally, this is currently untested with multiple
# replayers. I believe the correct solution would be to add a dimension for which replayer is
# being instrumented.


# CAPTURE PROXY
aws logs put-metric-filter \
    --log-group-name $CAPUTRE_PROXY_LOG_GROUP \
    --filter-name "CaptureProxy-FullRequestReceived" \
    --filter-pattern "{ ($.message  = \"Sent message to Kafka\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FullRequestReceived",metricNamespace="capture-proxy",metricValue=1,dimensions={"Method"="$.contextMap.httpMethod"},unit="Count"

aws logs put-metric-filter \
    --log-group-name $CAPUTRE_PROXY_LOG_GROUP \
    --filter-name "CaptureProxy-SentMessageToKafka" \
    --filter-pattern "{ ($.message  = \"Sent message to Kafka\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="SentMessageToKafka",metricNamespace="capture-proxy",metricValue=1,dimensions={"topic-name"="$.contextMap.topicName"},unit="Count"

aws logs put-metric-filter  \
    --log-group-name $CAPUTRE_PROXY_LOG_GROUP \
    --filter-name "CaptureProxy-SentMessageToKafkaBytes" \
    --filter-pattern "{ ($.message  = \"Sent message to Kafka\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="SentMessageToKafkaBytes",metricNamespace="capture-proxy",metricValue="$.contextMap.sizeInBytes",dimensions={"topic-name"="$.contextMap.topicName"},unit="Bytes"

aws logs put-metric-filter \
    --log-group-name $CAPUTRE_PROXY_LOG_GROUP \
    --filter-name "CaptureProxy-FailedMessageToKafka" \
    --filter-pattern "{ ($.message  = \"Sending message to Kafka failed.\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FailedMessageToKafka",metricNamespace="capture-proxy",metricValue=1,defaultValue=0,unit="Count"

# REPLAYER
aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-ParsedKafkaTrafficStream" \
    --filter-pattern "{ ($.message  = \"Parsed traffic stream from Kafka\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="ParsedKafkaTrafficStream",metricNamespace="traffic-replayer",metricValue=1,dimensions={"topic-name"="$.contextMap.topicName"},unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-FailedToParseKafkaTrafficStream" \
    --filter-pattern "{ ($.message  = \"Failed to parse traffic stream from Kafka.\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FailedToParseKafkaTrafficStream",metricNamespace="traffic-replayer",metricValue=1,defaultValue=0,unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-ParsedKafkaTrafficStreamBytes" \
    --filter-pattern "{ ($.message  = \"Parsed traffic stream from Kafka\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="ParsedKafkaTrafficStreamBytes",metricNamespace="traffic-replayer",metricValue="$.contextMap.sizeInBytes",dimensions={"topic-name"="$.contextMap.topicName"},unit="Bytes"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-CapturedRequestParsed" \
    --filter-pattern "{ ($.message  = \"Captured request parsed to HTTP\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="CapturedRequestParsed",metricNamespace="traffic-replayer",metricValue=1,dimensions={"Method"="$.contextMap.httpMethod"},unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-RequestTransformed" \
    --filter-pattern "{ ($.message  = \"Request was transformed\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="RequestTransformed",metricNamespace="traffic-replayer",metricValue=1,unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-FailedToTransformRequest" \
    --filter-pattern "{ ($.message  = \"Request failed to be transformed\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FailedToTransformRequest",metricNamespace="traffic-replayer",metricValue=1,defaultValue=0,unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-RequestScheduledToBeSent" \
    --filter-pattern "{ ($.message  = \"Request scheduled to be sent\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="RequestScheduledToBeSent",metricNamespace="traffic-replayer",metricValue=1,unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-RequestScheduledToBeSentDelay" \
    --filter-pattern "{ ($.message  = \"Request scheduled to be sent\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="RequestScheduledToBeSentDelay",metricNamespace="traffic-replayer",metricValue="$.contextMap.delayFromOriginalToScheduledStartInMs",unit="Milliseconds"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-FailedRequestComponent" \
    --filter-pattern "{ ($.message  = \"Failed to write component of request\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FailedRequestComponent",metricNamespace="traffic-replayer",metricValue=1,defaultValue=0,unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-RequestComponentWritten" \
    --filter-pattern "{ ($.message  = \"Component of request written to target\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="RequestComponentWritten",metricNamespace="traffic-replayer",metricValue=1,unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-RequestComponentWrittenBytes" \
    --filter-pattern "{ ($.message  = \"Component of request written to target\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="RequestComponentWrittenBytes",metricNamespace="traffic-replayer",metricValue="$.contextMap.sizeInBytes",unit="Bytes"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-FullResponseReceived" \
    --filter-pattern "{ ($.message  = \"Full response received\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FullResponseReceived",metricNamespace="traffic-replayer",metricValue=1,dimensions={"Status"="$.contextMap.httpStatus"},unit="Count"

aws logs put-metric-filter \
    --log-group-name $TRAFFIC_REPLAYER_LOG_GROUP \
    --filter-name "TrafficReplayer-FailedToReceiveResponse" \
    --filter-pattern "{ ($.message  = \"Failed to receive full response\") && ($.loggerName = \"MetricsLogger.*\") }" \
    --metric-transformations metricName="FailedToReceiveResponse",metricNamespace="traffic-replayer",metricValue=1,defaultValue=0,unit="Count"