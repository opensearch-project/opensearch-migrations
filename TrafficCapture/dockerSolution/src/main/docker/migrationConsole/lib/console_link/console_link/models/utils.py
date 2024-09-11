from botocore import config
from enum import Enum
from typing import Dict, Optional
from datetime import datetime
import boto3

from console_link.models.client_options import ClientOptions


class AWSAPIError(Exception):
    def __init__(self, message, status_code=None):
        super().__init__("Error encountered calling an AWS API", message, status_code)


def raise_for_aws_api_error(response: Dict) -> None:
    if (
        "ResponseMetadata" in response
        and "HTTPStatusCode" in response["ResponseMetadata"]  # noqa: W503
    ):
        status_code = response["ResponseMetadata"]["HTTPStatusCode"]
    else:
        raise AWSAPIError("ResponseMetadata was not found in the response")
    if status_code not in range(200, 300):
        raise AWSAPIError(
            "Non-2XX status code received",
            status_code=status_code
        )


class ExitCode(Enum):
    SUCCESS = 0
    FAILURE = 1


def generate_log_file_path(topic: str) -> str:
    now = datetime.now().isoformat()
    return f"{now}-{topic}.log"


def create_boto3_client(aws_service_name: str, region: Optional[str] = None,
                        client_options: Optional[ClientOptions] = None):
    client_config = None
    if client_options and client_options.user_agent_extra:
        user_agent_extra_param = { "user_agent_extra": client_options.user_agent_extra }
        client_config = config.Config(**user_agent_extra_param)
    return boto3.client(aws_service_name, region_name=region, config=client_config)
