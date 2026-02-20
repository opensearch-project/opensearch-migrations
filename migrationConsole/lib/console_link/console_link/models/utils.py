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
        return f"Running={self.running} Pending={self.pending} Desired={self.desired} Terminating={self.terminating}"


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


def _map_sigv4_config(sigv4_config) -> dict:
    sigv4_mapped = {}
    if isinstance(sigv4_config, dict):
        if "region" in sigv4_config:
            sigv4_mapped["region"] = sigv4_config["region"]
        if "service" in sigv4_config:
            sigv4_mapped["service"] = sigv4_config["service"]
    return {"sigv4": sigv4_mapped if sigv4_mapped else None}


def _map_basic_auth_config(basic_config) -> dict:
    if not isinstance(basic_config, dict):
        raise ValueError("authConfig.basic must be a dictionary")

    if "secretName" in basic_config:
        return {"basic_auth": {"k8s_secret_name": basic_config["secretName"]}}

    if "secretArn" in basic_config:
        return {"basic_auth": {"user_secret_arn": basic_config["secretArn"]}}

    if "username" in basic_config and "password" in basic_config:
        return {"basic_auth": {"username": basic_config["username"], "password": basic_config["password"]}}

    raise ValueError("authConfig.basic must contain either a secret or username/password")


def _map_cluster_auth_from_workflow_config(auth_config) -> dict:
    if auth_config is None or not isinstance(auth_config, dict):
        return {"no_auth": None}

    if "sigv4" in auth_config:
        return _map_sigv4_config(auth_config.get("sigv4"))

    if "mtls" in auth_config:
        raise NotImplementedError("MTLS auth is not currently supported.")

    if "basic" not in auth_config:
        raise ValueError(f"authConfig seems to be an unsupported format: {list(auth_config.keys())}. "
                         "Supported formats are SigV4, mTLS, and basic auth.")

    return _map_basic_auth_config(auth_config["basic"])


def map_cluster_from_workflow_config(workflow_config_obj) -> dict:
    """ Map from a workflow config format to services.yaml format.

    This is a bit of a hacky way to map from the cluster definition in a workflow config object to
    a services.yaml type config dictionary (defined by the schema in this file) that can be used to init
    a Cluster.
    """

    if "endpoint" not in workflow_config_obj:
        raise ValueError("The cluster data from the workflow config does not contain an 'endpoint' field")

    # Start building the mapped config
    mapped_config = {
        "endpoint": workflow_config_obj["endpoint"]
    }

    # Map allowInsecure -> allow_insecure
    if "allowInsecure" in workflow_config_obj:
        mapped_config["allow_insecure"] = workflow_config_obj["allowInsecure"]

    # Handle authentication configuration
    auth_config = workflow_config_obj.get("authConfig")
    mapped_config.update(_map_cluster_auth_from_workflow_config(auth_config))

    return mapped_config
