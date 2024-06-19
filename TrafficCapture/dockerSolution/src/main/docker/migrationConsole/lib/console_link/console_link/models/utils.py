# define a custom exception for aws api errors
from typing import Dict


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
