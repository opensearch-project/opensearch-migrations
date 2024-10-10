import argparse
import boto3
import logging
import re
import time
from typing import List

from botocore.exceptions import ClientError

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

INDEPENDENT_STACKS = ['MigrationConsole', 'ReindexFromSnapshot', 'TrafficReplayer', 'TargetClusterProxy',
                      'CaptureProxy', 'KafkaBroker', 'OpenSearchContainer', 'CaptureProxyES', 'Elasticsearch']
CORE_STACKS_ORDERED = ['MigrationInfra', 'OpenSearchDomain', 'NetworkInfra', 'infra-stack', 'network-stack']
CFN_INITIAL_STATUS_SKIP = ['DELETE_IN_PROGRESS', 'DELETE_COMPLETE']
MAX_DELETE_STACK_RETRIES = 3
MAX_WAIT_MINUTES = 45
WAIT_INTERVAL_SECONDS = 15


class DeleteStackFailure(Exception):
    pass


class DeleteStackTimeout(Exception):
    pass


class StackDeletionRequest:
    def __init__(self, stack_name):
        self.stack_name = stack_name
        self.retry_count = 0


def delete_stack(cfn_client, stack_name: str) -> StackDeletionRequest:
    try:
        describe_stack_response = cfn_client.describe_stacks(StackName=stack_name)
        stack_status = describe_stack_response['Stacks'][0]['StackStatus']
    except ClientError as client_error:
        if 'does not exist' in client_error.response['Error']['Message']:
            logger.warning(f"Stack {stack_name} no longer exists, skipping its deletion")
            return StackDeletionRequest(stack_name=stack_name)
        else:
            raise client_error
    if 'IN_PROGRESS' in stack_status:
        logger.warning(f"Unexpected status: {stack_status} for {stack_name} when preparing to delete stack")
    logger.info(f"Deleting stack: {stack_name}")
    cfn_client.delete_stack(StackName=stack_name)
    return StackDeletionRequest(stack_name=stack_name)


def retry_delete_stack(cfn_client, deletion_request: StackDeletionRequest):
    if deletion_request.retry_count >= MAX_DELETE_STACK_RETRIES:
        raise DeleteStackFailure(f"Max attempts of {MAX_DELETE_STACK_RETRIES} have failed to delete "
                                 f"stack: {deletion_request.stack_name}. Please see CFN stack logs for more details")
    logger.info(f"Retry attempt {deletion_request.retry_count + 1} of {MAX_DELETE_STACK_RETRIES} for "
                f"stack: {deletion_request.stack_name}")
    delete_stack(cfn_client=cfn_client, stack_name=deletion_request.stack_name)
    deletion_request.retry_count += 1
    return deletion_request


def wait_for_stack_deletion(cfn_client, stack_delete_requests: List[StackDeletionRequest]):
    wait_time_seconds = 0
    remaining_requests = stack_delete_requests[:]

    while remaining_requests and wait_time_seconds < (MAX_WAIT_MINUTES * 60):
        # Temporary list for stacks that are still being deleted
        in_progress_requests = []

        for delete_request in remaining_requests:
            stack_status = ""
            try:
                describe_stack_response = cfn_client.describe_stacks(StackName=delete_request.stack_name)
                stack_status = describe_stack_response['Stacks'][0].get('StackStatus')
            except ClientError as client_error:
                if 'does not exist' in client_error.response['Error']['Message']:
                    continue

            if stack_status == 'DELETE_COMPLETE':
                logger.info(f"Stack {delete_request.stack_name} deletion completed.")
            elif stack_status == 'DELETE_FAILED':
                logger.error(f"Stack {delete_request.stack_name} deletion failed, retrying...")
                retry_delete_stack(cfn_client=cfn_client, deletion_request=delete_request)
                in_progress_requests.append(delete_request)  # Keep for further checks after retry
            elif stack_status == 'DELETE_IN_PROGRESS':
                logger.info(f"Stack {delete_request.stack_name} is currently DELETE_IN_PROGRESS.")
                in_progress_requests.append(delete_request)  # Still in progress
            else:
                logger.warning(f"Unexpected status: {stack_status} for stack: {delete_request.stack_name}")
                in_progress_requests.append(delete_request)  # Unexpected status but still in progress

        remaining_requests = in_progress_requests
        if remaining_requests:
            logger.info(f"Waiting for the following stacks: {[r.stack_name for r in remaining_requests]}")

        time.sleep(WAIT_INTERVAL_SECONDS)
        wait_time_seconds += WAIT_INTERVAL_SECONDS

    if remaining_requests:
        raise DeleteStackTimeout(f"Timeout reached. The following stacks were still in "
                                 f"progress: {[r.stack_name for r in remaining_requests]}")
    else:
        logger.info(f"The following stacks have been deleted "
                    f"successfully: {[s.stack_name for s in stack_delete_requests]}")


def delete_stacks(cfn_client, stack_names):
    # Delete independent stacks in batch
    independent_stack_delete_requests = [
        delete_stack(cfn_client, stack_name)
        for stack_name in stack_names
        if any(stack_id in stack_name for stack_id in INDEPENDENT_STACKS)
    ]
    if independent_stack_delete_requests:
        wait_for_stack_deletion(cfn_client=cfn_client, stack_delete_requests=independent_stack_delete_requests)

    # Delete core stacks in order, and batch for a particular stack type
    for core_id in CORE_STACKS_ORDERED:
        core_delete_requests = []
        matching_stacks = [s for s in stack_names if core_id in s]
        for stack in matching_stacks:
            core_delete_requests.append(delete_stack(cfn_client, stack))
        if core_delete_requests:
            wait_for_stack_deletion(cfn_client=cfn_client, stack_delete_requests=core_delete_requests)


def delete_stacks_for_environment(stage_name: str):
    client = boto3.client('cloudformation')
    list_stacks_response = client.list_stacks()
    # https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/cloudformation/client/list_stacks.html
    stack_names = [stack['StackName'] for stack in list_stacks_response['StackSummaries']
                   if stack['StackStatus'] not in CFN_INITIAL_STATUS_SKIP]
    next_token = list_stacks_response.get("NextToken", None)
    # If list stacks response is paginated, continue till all stacks are retrieved
    while next_token is not None:
        next_list_stacks_response = client.list_stacks(NextToken=next_token)
        next_stack_names = [stack['StackName'] for stack in next_list_stacks_response['StackSummaries']
                            if stack['StackStatus'] not in CFN_INITIAL_STATUS_SKIP]
        stack_names.extend(next_stack_names)
        list_stacks_response.get("NextToken", None)

    stage_stack_names = []
    for name in stack_names:
        # Add stack that has stage name in middle(-stage-) or at end(-stage) of stack name
        if re.match(rf".*-{stage_name}-.*|.*-{stage_name}$", name):
            stage_stack_names.append(name)
    logging.info(f"Collected the following stacks to delete: {stage_stack_names}")
    delete_stacks(cfn_client=client, stack_names=stage_stack_names)


def main():
    parser = argparse.ArgumentParser(description="Cleanup an opensearch-migrations deployment environment.")
    parser.add_argument("--stage", type=str, help="The deployment stage environment to delete")
    args = parser.parse_args()

    start_time = time.time()
    delete_stacks_for_environment(args.stage)
    print(f"Total running time: {time.time() - start_time} seconds")


if __name__ == "__main__":
    main()
