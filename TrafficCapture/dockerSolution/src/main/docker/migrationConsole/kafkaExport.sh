#!/bin/bash

broker_endpoints="${MIGRATION_KAFKA_BROKER_ENDPOINTS}"
msk_auth_settings=""
kafka_command_settings=""
s3_bucket_name=""
partition_offsets=""
partition_limits=""
if [ -n "$ECS_AGENT_URI" ]; then
    msk_auth_settings="--kafka-traffic-enable-msk-auth"
    kafka_command_settings="--command-config aws/msk-iam-auth.properties"
    account_id=$(aws sts get-caller-identity --query Account --output text)
    s3_bucket_name="migration-artifacts-$account_id-$MIGRATION_STAGE-$AWS_REGION"
fi
topic="logging-traffic-topic"
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
  echo "  --s3-bucket-name                            Option to specify a given S3 bucket to store archive on."
  echo "  --partition-offsets                         Option to specify partition offsets in the format 'partition_id:offset,partition_id:offset'."
  echo "  --partition-limits                          Option to specify partition limits in the format 'partition_id:num_records,partition_id:num_records'."
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
        --partition-offsets)
            partition_offsets="$2"
            shift
            shift
            ;;
        --partition-limits)
            partition_limits="$2"
            shift
            shift
            ;;
        -h|--h|--help)
            usage
            ;;
        -*)
            echo "Unknown option $1"
            usage
            ;;
        *)
            shift
            ;;
    esac
done

if [ -n "$partition_offsets" ]; then
    # Prepend the topic name to each partition offset
    partition_offsets_with_topic=$(echo "$partition_offsets" | awk -v topic="$topic" 'BEGIN{RS=",";ORS=","}{print topic ":" $0}' | sed 's/,$//')
else
    partition_offsets_with_topic=""
fi

if [ -n "$partition_limits" ]; then
    # Prepend the topic name to each partition limit
    partition_limits_with_topic=$(echo "$partition_limits" | awk -v topic="$topic" 'BEGIN{RS=",";ORS=","}{print topic ":" $0}' | sed 's/,$//')
else
    partition_limits_with_topic=""
fi

partition_offsets=$(./kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list "$broker_endpoints" --topic "$topic" --time -1 $(echo "$kafka_command_settings"))
comma_sep_partition_offsets=$(echo $partition_offsets | sed 's/ /,/g')
echo "Collected offsets from current Kafka topic: "
echo $comma_sep_partition_offsets

epoch_ts=$(date +%s)
dir_name="kafka_export_$epoch_ts"
mkdir -p $dir_name
archive_name="kafka_export_from_migration_console_$epoch_ts.proto.gz"
group="exportFromMigrationConsole_$(hostname -s)_$$_$epoch_ts"
echo "Group name: $group"

# Construct the command dynamically
runJavaCmd="./runJavaWithClasspath.sh org.opensearch.migrations.replay.KafkaPrinter --kafka-traffic-brokers \"$broker_endpoints\" --kafka-traffic-topic \"$topic\" --kafka-traffic-group-id \"$group\" $msk_auth_settings --timeout-seconds \"$timeout_seconds\""

if [ -n "$partition_offsets_with_topic" ]; then
    runJavaCmd+=" --partition-offsets \"$partition_offsets_with_topic\""
fi

if [ -n "$partition_limits_with_topic" ]; then
    runJavaCmd+=" --partition-limits \"$partition_limits_with_topic\""
fi

# Execute the command
set -o xtrace
eval $runJavaCmd | gzip -c -9 > "dir_name/$archive_name"
set +o xtrace

# Remove created consumer group
./kafka/bin/kafka-consumer-groups.sh --bootstrap-server "$broker_endpoints" --timeout 100000 --delete --group "$group" $(echo "$kafka_command_settings")

if [ "$enable_s3" = true ]; then
  aws s3 mv "$dir_name/$archive_name" "s3://$s3_bucket_name" && echo "Export has been created: s3://$s3_bucket_name/$archive_name"
fi