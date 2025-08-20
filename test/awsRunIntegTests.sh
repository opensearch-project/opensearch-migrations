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
COMMAND="pipenv run pytest --log-file=${test_dir}/reports/${unique_id}/pytest.log --junitxml=${TEST_RESULT_FILE} ${test_dir}/replayer_tests.py --unique_id ${unique_id} -s"

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

# Detect test type from command to optimize execution path
if echo "${COMMAND}" | grep -q -E "(pytest|pipenv run pytest)"; then
    echo "Detected pytest command - will use XML report parsing if available"
    USE_XML_PARSING=true
else
    echo "Detected standalone script - will use exit code logic"
    USE_XML_PARSING=false
fi

# Execute integration test command
set -o xtrace
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "${COMMAND}"
command_exit_code=$?
set +o xtrace

echo "Command execution completed with exit code: $command_exit_code"

# Handle success/failure based on test type
if [ "$USE_XML_PARSING" = true ]; then
    echo "Using XML report parsing logic for pytest..."
    
    # For pytest commands, try XML parsing with fallback to exit code
    set -o xtrace
    test_output=$(unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "test -f ${TEST_RESULT_FILE} && awk '/failures/ && /errors/' ${TEST_RESULT_FILE} || echo 'XML_FILE_NOT_FOUND'")
    set +o xtrace
    
    echo "Integration test generated summary: "
    echo "$test_output"
    
    # Check if XML parsing was successful
    if echo "$test_output" | grep -q "XML_FILE_NOT_FOUND"; then
        echo "XML report file not found, falling back to exit code logic..."
        if [ $command_exit_code -eq 0 ]; then
            echo "Test passed based on successful command execution (exit code: 0)"
            exit 0
        else
            echo "Test failed based on command exit code: $command_exit_code"
            exit 1
        fi
    else
        # Parse XML report for failures and errors
        failure_output=$(echo "$test_output" | grep -o "failures=\"0\"")
        if [ -z "$failure_output" ]; then
            echo "Failed test detected in XML output, failing step"
            exit 1
        fi
        
        errors_output=$(echo "$test_output" | grep -o "errors=\"0\"")
        if [ -z "$errors_output" ]; then
            echo "Errored test detected in XML output, failing step"
            exit 1
        fi
        
        echo "XML report indicates success (failures=0, errors=0)"
        exit 0
    fi
else
    echo "Using exit code logic for standalone script..."
    
    # For standalone Python modules, use exit code directly (fastest path)
    if [ $command_exit_code -eq 0 ]; then
        echo "Integration test passed based on successful command execution (exit code: 0)"
        exit 0
    else
        echo "Integration test failed based on command exit code: $command_exit_code"
        exit 1
    fi
fi
