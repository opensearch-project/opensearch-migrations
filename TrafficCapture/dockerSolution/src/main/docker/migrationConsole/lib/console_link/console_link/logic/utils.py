# define a custom exception for aws api errors
from typing import Dict


class AWSAPIError(Exception):
    pass


def raise_for_aws_api_error(response: Dict) -> None:
    if (
        "ResponseMetadata" in response
        and "HTTPStatusCode" in response["ResponseMetadata"]  # noqa: W503
    ):
        status_code = response["ResponseMetadata"]["HTTPStatusCode"]
    else:
        raise AWSAPIError(
            "Error listing metrics from Cloudwatch"
        )  # TODO: handle this better
    if status_code != 200:
        raise AWSAPIError(
            "Error listing metrics from Cloudwatch"
        )  # TODO: handle this better
