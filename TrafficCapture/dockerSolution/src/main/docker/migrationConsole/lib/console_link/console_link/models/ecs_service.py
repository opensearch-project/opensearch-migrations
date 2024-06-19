import logging

import boto3

from console_link.models.command_result import CommandResult
from console_link.models.utils import AWSAPIError, raise_for_aws_api_error


logger = logging.getLogger(__name__)


class ECSService:
    def __init__(self, cluster_name, service_name, aws_region=None):
        self.cluster_name = cluster_name
        self.service_name = service_name
        self.aws_region = aws_region

        logger.info(f"Creating ECS client for region {aws_region}, if specified")
        self.client = boto3.client("ecs", region_name=self.aws_region)

    def set_desired_count(self, desired_count: int) -> CommandResult:
        logger.info(f"Setting desired count for service {self.service_name} to {desired_count}")
        response = self.client.update_service(
            cluster=self.cluster_name,
            service=self.service_name,
            desiredCount=desired_count
        )
        logger.debug(f"Response from update_service: {response}")

        try:
            raise_for_aws_api_error(response)
        except AWSAPIError as e:
            return CommandResult(False, e)

        logger.info(f"Service status: {response['service']['status']}")
        running_count = response["service"]["runningCount"]
        pending_count = response["service"]["pendingCount"]
        desired_count = response["service"]["desiredCount"]
        return CommandResult(True, f"Service {self.service_name} set to {desired_count} desired count."
                             f" Currently {running_count} running and {pending_count} pending.")
