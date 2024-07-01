#!/bin/bash

usage() {
  echo ""
  echo "Script to run integrations tests on AWS Migration Console"
  echo ""
  echo "Usage: "
  echo "  ./awsRunIntegTests.sh [--unique-id] [--stage]"
  echo ""
  echo "Options:"
  echo "  --unique-id                                      Identifier for labeling integ test artifacts, e.g. 'full_run_123'."
  echo "  --stage                                          The stage used for CDK deployment, default is 'aws-integ'."
  echo ""
  exit 1
}

epoch_seconds=$(date +%s)
UNIQUE_ID="test_${epoch_seconds}_1"
STAGE='aws-integ'

while [[ $# -gt 0 ]]; do
  case $1 in
    --unique-id)
      UNIQUE_ID="$2"
      shift # past argument
      shift # past value
      ;;
    --stage)
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

task_arn=$(aws ecs list-tasks --cluster migration-${STAGE}-ecs-cluster --family "migration-${STAGE}-migration-console" | jq --raw-output '.taskArns[0]')

test_dir="/root/lib/integ_test/integ_test"
# Kickoff integration tests
set -o xtrace
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "pytest --log-file=${test_dir}/reports/${UNIQUE_ID}/pytest.log --junitxml=${test_dir}/reports/${UNIQUE_ID}/report.xml ${test_dir}/replayer_tests.py --unique_id ${UNIQUE_ID} -k test_replayer_0004 -s"
set +o xtrace
test_output=$(unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "awk '/failures/ && /errors/' /root/lib/integ_test/integ_test/reports/${UNIQUE_ID}/report.xml")
echo "Integration test generated summary: "
echo "$test_output"
failure_output=$(echo "$test_output" | grep -o "failures=\"0\"")
if [ -z "$failure_output" ]; then
  echo "Failed test detected in output, failing step"
  exit 1
fi
errors_output=$(echo "$test_output" | grep -o "errors=\"0\"")
if [ -z "$errors_output" ]; then
  echo "Errored test detected in output, failing step"
  exit 1
fi
exit 0
