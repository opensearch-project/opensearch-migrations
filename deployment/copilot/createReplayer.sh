#!/bin/bash

# Wish list
# * Allow adding additional security group ids, https://opensearch.atlassian.net/browse/MIGRATIONS-1305

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd $script_dir_abs_path

usage() {
  echo ""
  echo "Create and Deploy Copilot Replayer service"
  echo ""
  echo "Usage: "
  echo "  ./createReplayer.sh <--id STRING> <--target-uri URI> [--kafka-group-id STRING, --extra-args STRING, --delete-id STRING, --copilot-app-name STRING, --skip-copilot-init, --region STRING, --stage STRING]"
  echo ""
  echo "Options:"
  echo "  --id                                  [string] The unique ID to give this particular Replayer service, will be used in service naming (e.g. traffic-replayer-ID)"
  echo "  --target-uri                          [string] The URI of the target cluster that captured requests will be replayed to (e.g. https://my-target-cluster.com:443)"
  echo "  --kafka-group-id                      [string, default: logging-group-<ID>] The Kafka consumer group ID the Replayer will use, if not specified an ID will be generated"
  echo "  --extra-args                          [string, default: null] Extra arguments to provide to the Replayer command (e.g. --extra-args '--sigv4-auth-header-service-region es,us-east-1')"
  echo "  --delete-id                           [string, default: null] Delete the Replayer directory with the given ID (e.g. traffic-replayer-ID) and remove the Copilot service"
  echo "  --copilot-app-name                    [string, default: migration-copilot] Specify the Copilot application name to use for deployment"
  echo "  --skip-copilot-init                   Skip one-time Copilot initialization of Replayer service"
  echo "  -t, --tags                            Pass AWS tags as key-value pairs. E.g., migrations=0.1,replayer=1"
  echo "  -r, --region                          [string, default: us-east-1] Specify the AWS region to deploy the CloudFormation stack and resources."
  echo "  -s, --stage                           [string, default: dev] Specify the stage name to associate with the deployed resources"
  echo ""
  exit 1
}

ID=""
TARGET_URI=""
KAFKA_GROUP_ID=""
EXTRA_ARGS=""
ID_TO_DELETE=""
COPILOT_APP_NAME=migration-copilot
SKIP_COPILOT_INIT=false
REGION=us-east-1
TAGS=migration_deployment=unknown
STAGE=dev
while [[ $# -gt 0 ]]; do
  case $1 in
    --id)
      ID="$2"
      shift # past argument
      shift # past value
      ;;
    --target-uri)
      TARGET_URI="$2"
      shift # past argument
      shift # past value
      ;;
    --kafka-group-id)
      KAFKA_GROUP_ID="$2"
      shift # past argument
      shift # past value
      ;;
    --extra-args)
      EXTRA_ARGS="$2"
      shift # past argument
      shift # past value
      ;;
    --delete-id)
      ID_TO_DELETE="$2"
      shift # past argument
      shift # past value
      ;;
    --copilot-app-name)
      COPILOT_APP_NAME="$2"
      shift # past argument
      shift # past value
      ;;
    --skip-copilot-init)
      SKIP_COPILOT_INIT=true
      shift # past argument
      ;;
    -t|--tags)
      TAGS="$2"
      shift # past argument
      shift # past value
      ;;
    -r|--region)
      REGION="$2"
      shift # past argument
      shift # past value
      ;;
    -s|--stage)
      STAGE="$2"
      shift # past argument
      shift # past value
      ;;
    -h|--help)
      usage
      ;;
    -*)
      echo "Unknown option $1"
      usage
      ;;
    *)
      shift # past argument
      ;;
  esac
done


# Remove service from Copilot and delete created service directory
if [[ -n "${ID_TO_DELETE}" ]]; then
  copilot svc delete -a $COPILOT_APP_NAME --name traffic-replayer-$ID_TO_DELETE --yes
  rm -r traffic-replayer-$ID_TO_DELETE
  exit 1
fi

# Check required parameters
if [[ -z "${ID}" || -z "${TARGET_URI}" ]]; then
  echo "Missing at least one required parameter: [--id, --target-uri]"
  exit 1
fi

# Load environment variables generated from devDeploy.sh
ENV_EXPORTS_FILE="./environments/${STAGE}/envExports.sh"
if [ ! -f "${ENV_EXPORTS_FILE}" ]; then
  echo "Required exports file ${ENV_EXPORTS_FILE} does not exist. This file will get generated when deploying the ./devDeploy.sh script"
  exit 1
else
  echo "Loading environment from ${ENV_EXPORTS_FILE}"
  source "${ENV_EXPORTS_FILE}"
fi

SERVICE_NAME="traffic-replayer-${ID}"
if [[ -z "${KAFKA_GROUP_ID}" ]]; then
  KAFKA_GROUP_ID="logging-group-${ID}"
fi

SERVICE_DIR="./${SERVICE_NAME}"
if [ ! -d "${SERVICE_DIR}" ]; then
  echo "Service directory: ${SERVICE_DIR} does not exist. Creating from ./templates/traffic-replayer"
  cp -r ./templates/traffic-replayer/. "${SERVICE_DIR}"
  sed -i.bak "s/SERVICE_NAME_PLACEHOLDER/${SERVICE_NAME}/g" "./${SERVICE_NAME}/manifest.yml" && rm "./${SERVICE_NAME}/manifest.yml.bak"
fi

if [ "${SKIP_COPILOT_INIT}" = false ] ; then
  copilot svc init -a "${COPILOT_APP_NAME}" --name "${SERVICE_NAME}"
fi

REPLAY_COMMAND="/bin/sh -c \"/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${TARGET_URI} --insecure --kafka-traffic-brokers ${MIGRATION_KAFKA_BROKER_ENDPOINTS} --kafka-traffic-topic logging-traffic-topic --kafka-traffic-group-id ${KAFKA_GROUP_ID} --kafka-traffic-enable-msk-auth ${EXTRA_ARGS}\""
echo "Constructed replay command: ${REPLAY_COMMAND}"
export MIGRATION_REPLAYER_COMMAND="${REPLAY_COMMAND}"

copilot svc deploy -a "${COPILOT_APP_NAME}" --name "${SERVICE_NAME}" --env "${STAGE}" --resource-tags ${TAGS}