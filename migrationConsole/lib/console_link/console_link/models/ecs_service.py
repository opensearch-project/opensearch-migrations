import logging
from typing import Optional

from console_link.models.command_result import CommandResult
from console_link.models.utils import AWSAPIError, DeploymentStatus, raise_for_aws_api_error, create_boto3_client

logger = logging.getLogger(__name__)


class ECSService:
    def __init__(self, cluster_name, service_name, aws_region=None, client_options=None):
        self.cluster_name = cluster_name
        self.service_name = service_name
        self.aws_region = aws_region
        self.client_options = client_options

        logger.info(f"Creating ECS client for region {aws_region}, if specified")
        self.client = create_boto3_client(aws_service_name="ecs", region=self.aws_region,
                                          client_options=self.client_options)

    def set_desired_count(self, desired_count: int) -> CommandResult:
        logger.info(f"Setting desired count for service {self.service_name} to {desired_count}")
        response = self.client.update_service(
            cluster=self.cluster_name,
            service=self.service_name,
            desiredCount=desired_count
        )

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

    def get_instance_statuses(self) -> Optional[DeploymentStatus]:
        logger.info(f"Getting instance statuses for service {self.service_name}")
        response = self.client.describe_services(
            cluster=self.cluster_name,
            services=[self.service_name]
        )
        try:
            raise_for_aws_api_error(response)
        except AWSAPIError as e:
            logger.error(f"Error getting instance statuses: {e}")
            return None

        service = response["services"][0]
        return DeploymentStatus(
            running=service["runningCount"],
            pending=service["pendingCount"],
            desired=service["desiredCount"]
        )
