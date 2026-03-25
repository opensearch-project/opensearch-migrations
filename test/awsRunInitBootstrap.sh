#!/bin/bash

usage() {
  echo ""
  echo "Script to run initBootstrap.sh on Migration Assistant bootstrap box"
  echo ""
  echo "Usage: "
  echo "  ./awsRunInitBootstrap.sh  [--stage] [--workflow] [--log-group-name]"
  echo ""
  echo "Options:"
  echo "  --stage                                     Deployment stage name, e.g. sol-integ"
  echo "  --workflow                                  Workflow to execute, options include ALL(default)|INIT_BOOTSTRAP|VERIFY_INIT_BOOTSTRAP"
  echo "  --log-group-name                            The CloudWatch log group name to produce logs into (Will create if doesn't exist)"
  echo ""
  exit 1
}

STAGE="aws-integ"
WORKFLOW="ALL"
REGION="us-east-1"
LOG_GROUP_NAME=""
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
    --log-group-name)
      LOG_GROUP_NAME="$2"
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

if [ -z "${LOG_GROUP_NAME}" ]; then
  echo "--log-group-name is a required parameter"
  usage
fi

execute_command_and_wait_for_result() {
  local command="$1"
  local instance_id="$2"
  local timeout="$3"
  echo "Executing command: [$command] on node: $instance_id with timeout of $timeout seconds"
  command_id=$(aws ssm send-command \
    --instance-ids "$instance_id" \
    --document-name "AWS-RunShellScript" \
    --parameters "{\"commands\":[\"$command\"],\"executionTimeout\":[\"$timeout\"]}" \
    --cloud-watch-output-config "CloudWatchOutputEnabled=true,CloudWatchLogGroupName=$LOG_GROUP_NAME" \
    --output text \
    --query 'Command.CommandId')
  if [[ -z "$command_id" ]]; then
    echo "Error: Unable to retrieve command id from triggered SSM command"
    exit 1
  fi
  sleep 5

  # Cleanup tail on completion/error/exit
  cleanup_tail() {
    echo "Cleaning up tail process..."
    if [[ -n "$tail_pid" ]]; then
      echo "Removing tail process"
      kill "$tail_pid" 2>/dev/null || true
      echo "Waiting for tail process"
      wait "$tail_pid" 2>/dev/null || true
    fi
  }
  trap 'cleanup_tail; exit 130' INT TERM
  trap cleanup_tail EXIT

  # Tail CW logs while command is running
  echo "Producing logs in CloudWatch log group: $LOG_GROUP_NAME "
  echo "----- Tailing logs from CloudWatch ------------"
  aws logs tail "$LOG_GROUP_NAME" --follow --since 15s &
  tail_pid=$!

  # Watch command for terminal state
  command_status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'Status')
  while [ "$command_status" != "Success" ] && [ "$command_status" != "Failed" ] && [ "$command_status" != "TimedOut" ]
  do
    sleep 5
    command_status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'Status')
  done
  echo "-----------------------------------------------"
  echo "Command has completed with status: $command_status, see full logs in CloudWatch log group $LOG_GROUP_NAME"
  echo "----- Completed SSM Command Output ------------"
  echo "Standard Output:"
  aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'StandardOutputContent'
  echo "Standard Error:"
  aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance_id" --output text --query 'StandardErrorContent'
  echo "-----------------------------------------------"

  if [[ "$command_status" != "Success" ]]; then
    echo "Error: Command [$command] was not successful and ended with status $command_status, see full logs in CloudWatch log group $LOG_GROUP_NAME"
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

check_ssm_ready() {
    local instance_id="$1"
    local timeout="${2:-60}"   # default 60 seconds
    local interval="${3:-5}"   # default 5 seconds
    local elapsed=0
    local ssm_status

    echo "Checking for SSM registration of instance ${instance_id}..."

    while [ $elapsed -lt $timeout ]; do
        ssm_status=$(aws ssm describe-instance-information \
            --filters "Key=InstanceIds,Values=${instance_id}" \
            --query "InstanceInformationList[0].PingStatus" --output text)

        if [ -z "$ssm_status" ] || [ "$ssm_status" = "None" ]; then
            echo "Instance ${instance_id} is not currently registered with SSM."
        elif [ "$ssm_status" != "Online" ]; then
            echo "Instance ${instance_id} SSM PingStatus is not Online. Current status: ${ssm_status}"
        else
            echo "Instance ${instance_id} is ready (running and SSM PingStatus is Online)."
            return 0
        fi

        sleep "$interval"
        elapsed=$((elapsed + interval))
    done

    echo "Instance ${instance_id} was not ready for SSM after ${timeout} seconds."
    exit 1
}

instance_id=$(get_instance_id)
check_ssm_ready "$instance_id"
init_command="cd /opensearch-migrations && ./initBootstrap.sh --branch main"
verify_command="cdk --version && docker --version && java --version && python3 --version"
init_command_timeout="4200" # 70 minutes
verify_command_timeout="300" # 5 minutes
if [ "$WORKFLOW" = "ALL" ]; then
  execute_command_and_wait_for_result "$init_command" "$instance_id" "$init_command_timeout"
  execute_command_and_wait_for_result "$verify_command" "$instance_id" "$verify_command_timeout"
elif [ "$WORKFLOW" = "INIT_BOOTSTRAP" ]; then
  execute_command_and_wait_for_result "$init_command" "$instance_id" "$init_command_timeout"
elif [ "$WORKFLOW" = "VERIFY_INIT_BOOTSTRAP" ]; then
  execute_command_and_wait_for_result "$verify_command" "$instance_id" "$verify_command_timeout"
else
  echo "Error: Unknown workflow: ${WORKFLOW} specified"
fi