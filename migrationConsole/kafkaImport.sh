#!/bin/bash

# Streams a captured-traffic export (gzipped, base64-encoded TrafficStream
# records produced by kafkaExport.sh) from S3 onto a Kafka topic. The export
# format and the kafkaUtils CLI are symmetric: every record loaded here
# matches the bytes of the original capture, so the replayer reads it the
# same way as live-captured traffic.

set -euo pipefail

broker_endpoints="${MIGRATION_KAFKA_BROKER_ENDPOINTS:-}"
kafka_property_file=""
input_uri=""
input_file=""
topic=""
batch_size=500
endpoint_url=""
auth_type=""

# MSK / EKS detection mirrors kafkaExport.sh so the script behaves the same
# in either deployment.
if [ -n "${ECS_AGENT_URI:-}" ]; then
    kafka_property_file="aws/msk-iam-auth.properties"
    auth_type="msk-iam"
fi

usage() {
  echo ""
  echo "Imports a kafkaExport.sh-produced archive into a Kafka topic."
  echo ""
  echo "Usage:"
  echo "  ./kafkaImport.sh --topic <topic> [OPTIONS]"
  echo ""
  echo "Source (one required):"
  echo "  --s3-uri <uri>           S3 URI of the gzipped export (e.g. s3://bucket/dump.proto.gz)."
  echo "  --input-file <path>      Path to a local gzipped export."
  echo ""
  echo "Required:"
  echo "  --topic <name>           Kafka topic to load records into. Topic must already exist."
  echo ""
  echo "Optional:"
  echo "  --bootstrap-server <list>  Override broker list (defaults to \$MIGRATION_KAFKA_BROKER_ENDPOINTS)."
  echo "  --batch-size <n>         Producer batch size (default 500)."
  echo "  --endpoint-url <url>     S3 endpoint override (e.g. for LocalStack)."
  echo "  --auth-type <kind>       Override Kafka auth: none|msk-iam|scram-sha-512."
  echo "  --kafka-property-file <p>  Override kafka client properties file."
  echo "  --help                   Show this message."
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --s3-uri)            input_uri="$2"; shift 2;;
    --input-file)        input_file="$2"; shift 2;;
    --topic)             topic="$2"; shift 2;;
    --bootstrap-server)  broker_endpoints="$2"; shift 2;;
    --batch-size)        batch_size="$2"; shift 2;;
    --endpoint-url)      endpoint_url="$2"; shift 2;;
    --auth-type)         auth_type="$2"; shift 2;;
    --kafka-property-file) kafka_property_file="$2"; shift 2;;
    -h|--help)           usage;;
    *) echo "Unknown option: $1" >&2; usage;;
  esac
done

[[ -n "$topic" ]] || { echo "Missing --topic" >&2; usage; }
[[ -n "$broker_endpoints" ]] || { echo "Missing --bootstrap-server (and \$MIGRATION_KAFKA_BROKER_ENDPOINTS is unset)" >&2; usage; }
if [[ -z "$input_uri" && -z "$input_file" ]]; then
  echo "Provide either --s3-uri or --input-file" >&2; usage
fi
if [[ -n "$input_uri" && -n "$input_file" ]]; then
  echo "Provide only one of --s3-uri or --input-file" >&2; usage
fi

script_dir="$(dirname "$0")"
abs_script_dir="$(cd "$script_dir" && pwd)"

# Default kafkaUtils install path inside the migration-console image
# (sibling of kafka-tools). Override via $KAFKA_UTILS_BIN if needed.
kafka_utils_bin="${KAFKA_UTILS_BIN:-/root/kafkaUtils/bin/kafkaUtils}"
if [[ ! -x "$kafka_utils_bin" ]]; then
  alt="${abs_script_dir}/kafkaUtils/bin/kafkaUtils"
  if [[ -x "$alt" ]]; then
    kafka_utils_bin="$alt"
  fi
fi

loader_args=(
  --stdin
  --topicName "$topic"
  --kafkaBrokers "$broker_endpoints"
  --batchSize "$batch_size"
)
if [[ -n "$auth_type" ]]; then
  loader_args+=(--kafkaAuthType "$auth_type")
fi
if [[ -n "$kafka_property_file" ]]; then
  loader_args+=(--kafkaPropertyFile "$kafka_property_file")
fi

if [[ -n "$input_uri" ]]; then
  s3_args=()
  if [[ -n "$endpoint_url" ]]; then
    s3_args+=(--endpoint-url "$endpoint_url")
  fi
  echo "Importing $input_uri -> kafka://$broker_endpoints/$topic"
  aws "${s3_args[@]}" s3 cp "$input_uri" - | gunzip | "$kafka_utils_bin" "${loader_args[@]}"
else
  echo "Importing $input_file -> kafka://$broker_endpoints/$topic"
  gunzip -c "$input_file" | "$kafka_utils_bin" "${loader_args[@]}"
fi
