#!/bin/bash

usage() {
  echo ""
  echo "Script to run integrations tests on AWS Migration Console"
  echo ""
  echo "Usage: "
  echo "  ./awsRunIntegTests.sh [--command] [--test-result-file] [--stage]"
  echo ""
  echo "Options:"
  echo "  --command                                   Provide test command to execute on the Migration Console"
  echo "  --test-result-file                          The test result file to check for success or failure"
  echo "  --stage                                     Deployment stage name"
  echo ""
  exit 1
}

epoch_seconds=$(date +%s)
unique_id="test_${epoch_seconds}_1"
test_dir="/root/lib/integ_test/integ_test"
STAGE="aws-integ"
TEST_RESULT_FILE="${test_dir}/reports/${unique_id}/report.xml"
COMMAND="pytest --log-file=${test_dir}/reports/${unique_id}/pytest.log --junitxml=${TEST_RESULT_FILE} ${test_dir}/replayer_tests.py --unique_id ${unique_id} -s"

while [[ $# -gt 0 ]]; do
  case $1 in
    --command)
      COMMAND="$2"
      shift # past argument
      shift # past value
      ;;
    --test-result-file)
      TEST_RESULT_FILE="$2"
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

# Kickoff integration tests
set -o xtrace
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "${COMMAND}"
test_output=$(unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "awk '/failures/ && /errors/' ${TEST_RESULT_FILE}")
set +o xtrace
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
