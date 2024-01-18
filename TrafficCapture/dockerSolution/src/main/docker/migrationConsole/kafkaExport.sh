#!/bin/bash

broker_endpoints="${MIGRATION_KAFKA_BROKER_ENDPOINTS}"
msk_auth_settings=""
kafka_command_settings=""
s3_bucket_name=""
if [ -n "$AWS_REGION" ]; then
    msk_auth_settings="--kafka-traffic-enable-msk-auth"
    kafka_command_settings="--command-config kafka-tools/aws/msk-iam-auth.properties"
    s3_bucket_name="migration-artifacts-$MIGRATION_STAGE-$AWS_REGION"
fi
timeout_seconds=60
enable_s3=false


usage() {
  echo ""
  echo "Utility script for exporting all currently detected Kafka records to a gzip file, and allowing the option to store this archive file on S3."
  echo ""
  echo "Usage: "
  echo "  ./kafkaExport.sh <>"
  echo ""
  echo "Options:"
  echo "  --timeout-seconds                           Timeout for how long process will try to collect the Kafka records. Default is 60 seconds."
  echo "  --enable-s3                                 Option to store created archive on S3."
  echo "  --s3-bucket-name                            Option to specify a given S3 bucket to store archive on".
  echo ""
  exit 1
}

while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --timeout-seconds)
            timeout_seconds="$2"
            shift
            shift
            ;;
        --enable-s3)
            enable_s3=true
            shift
            ;;
        --s3-bucket-name)
            s3_bucket_name="$2"
            shift
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            shift
            ;;
    esac
done

partition_offsets=$(./kafka-tools/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list "$broker_endpoints" --topic logging-traffic-topic --time -1 $(echo "$kafka_command_settings"))
# TODO update for multiple partitions
echo "Collected offsets from current Kafka topic: "
echo $partition_offsets

epoch_ts=$(date +%s)
file_name="kafka_export_$epoch_ts.log"
archive_name="$file_name.tar.gz"
group="export_$epoch_ts"

set -o xtrace
./runJavaWithClasspath.sh org.opensearch.migrations.replay.KafkaPrinter --kafka-traffic-brokers "$broker_endpoints" --kafka-traffic-topic logging-traffic-topic --kafka-traffic-group-id "$group" $(echo "$msk_auth_settings") --timeout-seconds "$timeout_seconds" --partition-limit "$partition_offsets" >> "$file_name"
set +o xtrace

tar -czvf "$archive_name" "$file_name" && rm "$file_name"

# Remove created consumer group
./kafka-tools/kafka/bin/kafka-consumer-groups.sh --bootstrap-server "$broker_endpoints" --timeout 100000 --delete --group "$group" $(echo "$kafka_command_settings")

if [ "$enable_s3" = true ]; then
  aws s3 mv "$archive_name" "s3://$s3_bucket_name" && echo "Export has been created: s3://$s3_bucket_name/$archive_name"
fi