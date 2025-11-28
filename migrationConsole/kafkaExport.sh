#!/bin/bash

broker_endpoints="${MIGRATION_KAFKA_BROKER_ENDPOINTS}"
kafka_command_config=""
kafka_consumer_config=""
s3_bucket_name=""

if [ -n "$ECS_AGENT_URI" ]; then
    kafka_command_config="--command-config aws/msk-iam-auth.properties"
    kafka_consumer_config="--consumer.config aws/msk-iam-auth.properties"
    account_id=$(aws sts get-caller-identity --query Account --output text)
    s3_bucket_name="migration-artifacts-$account_id-$MIGRATION_STAGE-$AWS_REGION"
fi

script_dir="$(dirname "$0")"
abs_script_dir="$(cd "$script_dir" && pwd)"
output_dir="$abs_script_dir"
topic="logging-traffic-topic"
timeout_seconds=60
enable_s3=false

usage() {
  echo ""
  echo "Utility script for exporting Kafka records to a gzip file, including the option to store this archive file on S3."
  echo ""
  echo "Usage: "
  echo "  ./kafkaExport.sh [OPTIONS] [-- EXTRA_ARGS]"
  echo ""
  echo "Options:"
  echo "  --output-dir <string>               Specify the output directory the export GZIP file will be written to, e.g. '/shared-logs-output'. Default is the same directory of the script."
  echo "  --timeout-seconds <seconds>         Timeout for how long the process will try to collect the Kafka records. Default is 60 seconds."
  echo "  --enable-s3                         Option to store the created archive on S3."
  echo "  --s3-bucket-name <bucket_name>      Option to specify a given S3 bucket to store the archive."
  echo "  --help                              Display this help message."
  echo ""
  echo "Any arguments after '--' will be passed directly to kafka-console-consumer.sh."
  echo ""
  echo "Examples:"
  echo "  To export 2 messages from a topic from the beginning"
  echo "    ./kafkaExport.sh -- --max-messages 2 --from-beginning"
  echo ""
  echo "  To export messages from a partition from a specific offset"
  echo "    ./kafkaExport.sh -- --partition 0  --offset 30"
  exit 1
}

EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --output-dir)
            output_dir="$2"
            shift 2
            ;;
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
all_consumers_partition_offsets=$(./kafka/bin/kafka-run-class.sh org.apache.kafka.tools.GetOffsetShell --broker-list "$broker_endpoints" --topic "$topic" --time -1 $(echo "$kafka_command_config"))
comma_sep_all_consumers_partition_offsets="${all_consumers_partition_offsets// /,}"
echo "Existing offsets from current Kafka topic across all consumer groups: "
echo "$comma_sep_all_consumers_partition_offsets"
echo ""

epoch_ts=$(date +%s)
archive_name="kafka_export_from_migration_console_$epoch_ts.proto.gz"
archive_path="$output_dir/$archive_name"
s3_bucket_uri="s3://$s3_bucket_name/kafka-exports/$archive_name"
group="exportFromMigrationConsole_$(hostname -s)_$$_$epoch_ts"

export CLASSPATH="$CLASSPATH:$(find "$abs_script_dir" -name 'kafkaCommandLineFormatter-*.jar' | head -n 1)"

echo "Starting kafka export to $archive_path"
# Build the command as an array
cmd=(
  timeout "$timeout_seconds"
  ./kafka/bin/kafka-console-consumer.sh
  --bootstrap-server "$broker_endpoints"
  --topic "$topic"
  --group "$group"
  --property print.key=false
  --property print.timestamp=false
  --formatter org.opensearch.migrations.utils.kafka.Base64Formatter
  --property print.value=true
  --property value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer
)

# Add dynamic parts
read -ra kafka_args <<< "$kafka_consumer_config"
cmd+=("${kafka_args[@]}")
cmd+=("${EXTRA_ARGS[@]}")

# Print the full command as one string
echo "Running command:"
printf '%q ' "${cmd[@]}"
echo "| gzip -c -9 > \"$archive_path\""
echo ""

# Execute the command
"${cmd[@]}" | gzip -c -9 > "$archive_path"

if [ "$enable_s3" = true ]; then
  aws s3 mv "$archive_path" "$s3_bucket_uri" && echo "Export moved to S3: $s3_bucket_uri"
fi
