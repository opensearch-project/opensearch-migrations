#!/bin/bash

usage() {
  echo ""
  echo "Script to run initBootstrap.sh on Migration Assistant bootstrap box"
  echo ""
  echo "Usage: "
  echo "  ./awsRunInitBootstrap.sh  [--stage] [--workflow]--"
  echo ""
  echo "Options:"
  echo "  --stage                                     Deployment stage name, e.g. sol-integ"
  echo "  --workflow                                  Workflow to execute, options include ALL(default)|INIT_BOOTSTRAP|VERIFY_INIT_BOOTSTRAP"
  echo ""
  exit 1
}

STAGE="aws-integ"
WORKFLOW="ALL"
REGION="us-east-1"
while [[ $# -gt 0 ]]; do
  case $1 in
    --stage)
      STAGE="$2"
      shift # past argument
      shift # past value
      ;;
    --workflow)
      WORKFLOW="$2"
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

execute_command_and_wait_for_result() {
  local command="$1"
  local instance_id="$2"
  echo "Executing command: [$command] on node: $instance_id"
  command_id=$(aws ssm send-command --instance-ids "$instance_id" --document-name "AWS-RunShellScript" --parameters commands="$command" --output text --query 'Command.CommandId')
  if [[ -z "$command_id" ]]; then
    echo "Error: Unable to retrieve command id from triggered SSM command"
    exit 1
  fi
  sleep 5
  command_status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'Status')
  max_attempts=30
  attempt_count=0
  while [ "$command_status" != "Success" ] && [ "$command_status" != "Failed" ] && [ "$command_status" != "TimedOut" ]
  do
    ((attempt_count++))
    if [[ $attempt_count -ge $max_attempts ]]; then
      echo "Error: Command did not complete within the maximum retry limit."
      exit 1
    fi
    echo "Waiting for command to complete, current status is $command_status"
    sleep 60
    command_status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'Status')
  done
  echo "Command has completed with status: $command_status, appending output"
  echo "Standard Output:"
  aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'StandardOutputContent'
  echo "Standard Error:"
  aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'StandardErrorContent'

  if [[ "$command_status" != "Success" ]]; then
    echo "Error: Command [$command] was not successful, see logs above"
    exit 1
  fi
}

get_instance_id() {
  # Retrieve the instance ID
  instance_id=$(aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=bootstrap-instance-${STAGE}-${REGION}" "Name=instance-state-name,Values=running" \
    --query "Reservations[0].Instances[0].InstanceId" \
    --output text)

  if [[ -z "$instance_id" || "$instance_id" == "None" ]]; then
    echo "Error: Running bootstrap EC2 instance not found"
    exit 1
  fi
  echo "$instance_id"
}

instance_id=$(get_instance_id)
init_command="cd /opensearch-migrations && ./initBootstrap.sh"
verify_command="cdk --version && docker --version && java --version && python3 --version"
if [ "$WORKFLOW" = "ALL" ]; then
  execute_command_and_wait_for_result "$init_command" "$instance_id"
  execute_command_and_wait_for_result "$verify_command" "$instance_id"
elif [ "$WORKFLOW" = "INIT_BOOTSTRAP" ]; then
  execute_command_and_wait_for_result "$init_command" "$instance_id"
elif [ "$WORKFLOW" = "VERIFY_INIT_BOOTSTRAP" ]; then
  execute_command_and_wait_for_result "$verify_command" "$instance_id"
else
  echo "Error: Unknown workflow: ${WORKFLOW} specified"
fi