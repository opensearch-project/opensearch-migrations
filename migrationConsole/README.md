# Migration Console
The accessible control hub for all things migrations


## Exporting Captured Traffic in Kafka
From a deployed Migration Console, a user can export captured traffic that has been stored in Kafka to a gzip file locally or in a S3 bucket

### Access the kafka directory from the Migration Console
```shell
cd /root/kafka-tools
```

### Perform the desired Kafka export
There are many different parameters that can be provided to configure the export to be taken. Most of these parameters are from the `kafka-console-consumer.sh` utility that comes packaged with Kafka which we allow passing to this utility after the `--` separator. More details on the available arguments for this script can be found [here](https://docs.confluent.io/kafka/operations-tools/kafka-tools.html?#kafka-console-consumer-sh). See some specific examples below:

Export all current Kafka records as well as records that get added during the 2-minute limit (if possible) and store the gzip export in S3
```shell
./kafkaExport.sh --timeout-seconds 120 --enable-s3 -- --from-beginning
```

Export all current Kafka records as well as records that get added during the 5-minute limit (if possible) and store the gzip export in the `/shared-logs-output` EFS mounted directory
```shell
./kafkaExport.sh --timeout-seconds 300 --output-dir /shared-logs-output -- --from-beginning
```

Export only the first 200 messages from Kafka and store locally
```shell
./kafkaExport.sh -- --max-messages 200 --from-beginning
```