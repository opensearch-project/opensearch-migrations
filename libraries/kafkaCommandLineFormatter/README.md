This package immplements a kafka.tools.MessageFormatter that can be used with the kafka-console-consumer.sh
script that is packaged with kafka-tools.  

The provided org.opensearch.migrations.utils.kafka.Base64Formatter will write Base64 encoded byte[] data.

To use this, put the jar file from the package in the classpath and run

```
kafka-console-consumer.sh \
--bootstrap-server $BROKER_ENDPOINTS \
--topic $TOPIC_NAME \
--formatter org.opensearch.migrations.utils.kafka.Base64Formatter \
--property print.value=true \
--property value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer | while read -r message  \
do ; \
  do echo -n $message | base64 --w=0 && echo ;  \
done
```
