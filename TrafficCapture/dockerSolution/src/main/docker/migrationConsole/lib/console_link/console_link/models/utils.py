from botocore import config
from enum import Enum
from typing import Dict, NamedTuple, Optional
from datetime import datetime
import boto3
import requests.utils
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from requests.models import PreparedRequest
from urllib.parse import urlparse


from console_link.models.client_options import ClientOptions


DEFAULT_SNAPSHOT_REPO_NAME = "migration_assistant_repo"


class DeploymentStatus(NamedTuple):
    running: int = 0
    pending: int = 0
    desired: int = 0
    terminating: int = 0

    def __str__(self):
        return f"Running={self.running}\nPending={self.pending}\nDesired={self.desired}\nTerminating={self.terminating}"


class AWSAPIError(Exception):
    def __init__(self, message, status_code=None):
        super().__init__("Error encountered calling an AWS API", message, status_code)


def raise_for_aws_api_error(response: Dict) -> None:
    if (
        "ResponseMetadata" in response and
        "HTTPStatusCode" in response["ResponseMetadata"]  # noqa: W503
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
        user_agent_extra_param = {"user_agent_extra": client_options.user_agent_extra}
        client_config = config.Config(**user_agent_extra_param)
    return boto3.client(aws_service_name, region_name=region, config=client_config)


def append_user_agent_header_for_requests(headers: Optional[dict], user_agent_extra: str):
    adjusted_headers = dict(headers) if headers else {}
    if "User-Agent" in adjusted_headers:
        adjusted_headers["User-Agent"] = f"{adjusted_headers['User-Agent']} {user_agent_extra}"
    else:
        adjusted_headers["User-Agent"] = f"{requests.utils.default_user_agent()} {user_agent_extra}"
    return adjusted_headers

# The SigV4AuthPlugin allows us to use boto3 with the requests library by integrating
# AWS Signature Version 4 signing. This enables the requests library to authenticate
# requests to AWS services using SigV4.


class SigV4AuthPlugin(requests.auth.AuthBase):
    def __init__(self, service, region):
        self.service = service
        self.region = region
        session = boto3.Session()
        self.credentials = session.get_credentials()

    def __call__(self, r: PreparedRequest) -> PreparedRequest:
        # Exclude signing headers that may change after signing
        default_headers = requests.utils.default_headers()
        excluded_headers = default_headers.keys()

        # Opensearch has unique port signing behavior where it cannot be in the signature
        # Thus adding a custom Host header streamline auth edge cases
        r.headers['Host'] = urlparse(r.url).hostname

        filtered_headers = {k: v for k, v in r.headers.items() if k.lower() not in excluded_headers}
        aws_request = AWSRequest(method=r.method, url=r.url, data=r.body, headers=filtered_headers)
        signer = SigV4Auth(self.credentials, self.service, self.region)
        if aws_request.body is not None:
            aws_request.headers['x-amz-content-sha256'] = signer.payload(aws_request)
        signer.add_auth(aws_request)
        r.headers.update(dict(aws_request.headers))
        return r
