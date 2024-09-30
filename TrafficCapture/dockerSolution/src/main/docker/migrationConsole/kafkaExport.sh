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
  echo "  ./kafkaExport.sh [OPTIONS] [-- EXTRA_ARGS]"
  echo ""
  echo "Options:"
  echo "  --timeout-seconds <seconds>         Timeout for how long the process will try to collect the Kafka records. Default is 60 seconds."
  echo "  --enable-s3                         Option to store the created archive on S3."
  echo "  --s3-bucket-name <bucket_name>      Option to specify a given S3 bucket to store the archive."
  echo "  --help                              Display this help message."
  echo ""
  echo "Any arguments after '--' will be passed directly to kafka-console-consumer.sh."
  echo ""
  echo "Examples:"
  echo "  To export 2 messages from a topic from the beginning"
  echo "    ./kafkaExport.sh -- --group foo2 --max-messages 2 --from-beginning"
  echo ""
  echo "  To export messages from a partition from a specific offset"
  echo "    ./kafkaExport.sh -- --partition 0  --offset 30"
  exit 1
}

EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --timeout-seconds)
            timeout_seconds="$2"
            shift 2
            ;;
        --enable-s3)
            enable_s3=true
            shift
            ;;
        --s3-bucket-name)
            s3_bucket_name="$2"
            shift 2
            ;;
        -h|--h|--help)
            usage
            ;;
        --)
            shift
            EXTRA_ARGS=("$@")
            break
            ;;
        -*)
            echo "Unknown option: $1"
            usage
            ;;
        *)
            echo "Unknown argument: $1"
            usage
            ;;
    esac
done

# Printing existing offsets in topic
all_consumers_partition_offsets=$(./kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list "$broker_endpoints" --topic "$topic" --time -1 $(echo "$kafka_command_settings"))
comma_sep_all_consumers_partition_offsets="${all_consumers_partition_offsets// /,}"
echo "Existing offsets from current Kafka topic across all consumer groups: "
echo "$comma_sep_all_consumers_partition_offsets"

epoch_ts=$(date +%s)
dir_name="kafka_export_$epoch_ts"
mkdir -p $dir_name
archive_name="kafka_export_from_migration_console_$epoch_ts.proto.gz"
group="exportFromMigrationConsole_$(hostname -s)_$$_$epoch_ts"
echo "Group name: $group"

SCRIPT_DIR="$(dirname "$0")"
ABS_SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
export CLASSPATH="$CLASSPATH:${ABS_SCRIPT_DIR}/kafkaCommandLineFormatter.jar"

# Execute the command
set -o xtrace
timeout "$timeout_seconds" \
./kafka/bin/kafka-console-consumer.sh \
--bootstrap-server "$broker_endpoints" \
--topic "$topic" \
--property print.key=false \
--property print.timestamp=false \
--formatter org.opensearch.migrations.utils.kafka.Base64Formatter \
--property print.value=true \
--property value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer \
$(echo "$kafka_command_settings") \
"${EXTRA_ARGS[@]}" | \
gzip -c -9 > "$dir_name/$archive_name"
set +o xtrace

if [ "$enable_s3" = true ]; then
  aws s3 mv "$dir_name/$archive_name" "s3://$s3_bucket_name" && echo "Export has been created: s3://$s3_bucket_name/$archive_name"
fi
