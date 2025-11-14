from typing import Any, Dict, Generator, NamedTuple, Optional, TypeAlias
from enum import Enum
import json
import logging
import subprocess
from pydantic import BaseModel

import boto3
from cerberus import Validator
import requests
import requests.auth
from requests.auth import HTTPBasicAuth
from console_link.models.client_options import ClientOptions
from console_link.models.schema_tools import contains_one_of
from console_link.models.utils import SigV4AuthPlugin, create_boto3_client, append_user_agent_header_for_requests

requests.packages.urllib3.disable_warnings()  # ignore: type

logger = logging.getLogger(__name__)

AuthMethod = Enum("AuthMethod", ["NO_AUTH", "BASIC_AUTH", "SIGV4"])
HttpMethod = Enum("HttpMethod", ["GET", "POST", "PUT", "DELETE", "HEAD"])


NO_AUTH_SCHEMA = {
    "nullable": True,
}


def validate_basic_auth_options(field, value, error):
    username = value.get("username")
    password = value.get("password")
    user_secret_arn = value.get("user_secret_arn")

    has_user_pass = username is not None and password is not None
    has_user_secret = user_secret_arn is not None

    if has_user_pass and has_user_secret:
        error(field, "Cannot provide both (username + password) and user_secret_arn")
    elif has_user_pass and (username == "" or password == ""):
        error(field, "Both username and password must be non-empty")
    elif not has_user_pass and not has_user_secret:
        error(field, "Must provide either (username + password) or user_secret_arn")


BASIC_AUTH_SCHEMA = {
    "type": "dict",
    "schema": {
        "username": {
            "type": "string",
            "required": False,
        },
        "password": {
            "type": "string",
            "required": False,
        },
        "user_secret_arn": {
            "type": "string",
            "required": False,
        }
    },
    "check_with": validate_basic_auth_options
}

SIGV4_SCHEMA = {
    "nullable": True,
    "type": "dict",
    "schema": {
        "region": {"type": "string", "required": False},
        "service": {"type": "string", "required": False}
    }
}

SCHEMA = {
    "cluster": {
        "type": "dict",
        "schema": {
            "endpoint": {"type": "string", "required": True},
            "allow_insecure": {"type": "boolean", "required": False},
            "version": {"type": "string", "required": False},
            "no_auth": NO_AUTH_SCHEMA,
            "basic_auth": BASIC_AUTH_SCHEMA,
            "sigv4": SIGV4_SCHEMA
        },
        "check_with": contains_one_of({auth.name.lower() for auth in AuthMethod})
    }
}


class AuthDetails(NamedTuple):
    username: str
    password: str


class Cluster:
    """
    An elasticcsearch or opensearch cluster.
    """

    config: Dict
    endpoint: str = ""
    version: Optional[str] = None
    aws_secret_arn: Optional[str] = None
    auth_type: Optional[AuthMethod] = None
    auth_details: Optional[Dict[str, Any]] = None
    allow_insecure: bool = False
    client_options: Optional[ClientOptions] = None

    def __init__(self, config: Dict, client_options: Optional[ClientOptions] = None) -> None:
        logger.info(f"Initializing cluster with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate({'cluster': config}):
            raise ValueError("Invalid config file for cluster", v.errors)

        self.config = config
        self.endpoint = config["endpoint"]
        self.version = config.get("version", None)
        self.allow_insecure = config.get("allow_insecure", False) if self.endpoint.startswith(
            "https") else config.get("allow_insecure", True)
        if 'no_auth' in config:
            self.auth_type = AuthMethod.NO_AUTH
        elif 'basic_auth' in config:
            self.auth_type = AuthMethod.BASIC_AUTH
            self.auth_details = config["basic_auth"]
        elif 'sigv4' in config:
            self.auth_type = AuthMethod.SIGV4
            self.auth_details = config["sigv4"] if config["sigv4"] is not None else {}
        self.client_options = client_options

    def get_basic_auth_details(self) -> AuthDetails:
        """Return a tuple of (username, password) for basic auth. Will use username/password if provided in plaintext,
        otherwise will pull both username/password as keys in the specified secrets manager secret.
        """
        assert self.auth_type == AuthMethod.BASIC_AUTH
        assert self.auth_details is not None  # for mypy's sake
        if "username" in self.auth_details and "password" in self.auth_details:
            return AuthDetails(username=self.auth_details["username"], password=self.auth_details["password"])
        # Pull password from AWS Secrets Manager
        assert "user_secret_arn" in self.auth_details  # for mypy's sake
        client = create_boto3_client(aws_service_name="secretsmanager", client_options=self.client_options)
        secret_response = client.get_secret_value(SecretId=self.auth_details["user_secret_arn"])
        try:
            secret_dict = json.loads(secret_response["SecretString"])
        except json.JSONDecodeError:
            raise ValueError(f"Expected secret {self.auth_details['user_secret_arn']} to be a JSON object with username"
                             f" and password fields")

        missing_keys = [k for k in ("username", "password") if k not in secret_dict]
        if missing_keys:
            raise ValueError(
                f"Secret {self.auth_details['user_secret_arn']} is missing required key(s): {', '.join(missing_keys)}"
            )

        return AuthDetails(username=secret_dict["username"], password=secret_dict["password"])

    def _get_sigv4_details(self, force_region=False) -> tuple[str, Optional[str]]:
        """Return the service signing name and region name. If force_region is true,
        it will instantiate a boto3 session to guarantee that the region is not None.
        This will fail if AWS credentials are not available.
        """
        assert self.auth_type == AuthMethod.SIGV4
        if force_region and 'region' not in self.auth_details:
            session = boto3.session.Session()
            return self.auth_details.get("service", "es"), self.auth_details.get("region", session.region_name)
        return self.auth_details.get("service", "es"), self.auth_details.get("region", None)

    def _generate_auth_object(self) -> requests.auth.AuthBase | None:
        if self.auth_type == AuthMethod.BASIC_AUTH:
            assert self.auth_details is not None  # for mypy's sake
            auth_details = self.get_basic_auth_details()
            return HTTPBasicAuth(auth_details.username, auth_details.password)
        elif self.auth_type == AuthMethod.SIGV4:
            service_name, region_name = self._get_sigv4_details(force_region=True)
            return SigV4AuthPlugin(service_name, region_name)
        elif self.auth_type is AuthMethod.NO_AUTH:
            return None
        raise NotImplementedError(f"Auth type {self.auth_type} not implemented")

    def call_api(self, path, method: HttpMethod = HttpMethod.GET, data=None, headers=None,
                 timeout=None, session=None, raise_error=True, **kwargs) -> requests.Response:
        """
        Calls an API on the cluster.
        """
        if session is None:
            session = requests.Session()

        auth = self._generate_auth_object()

        request_headers = headers
        if self.client_options and self.client_options.user_agent_extra:
            user_agent_extra = self.client_options.user_agent_extra
            request_headers = append_user_agent_header_for_requests(headers=headers, user_agent_extra=user_agent_extra)

        # Extract query parameters from kwargs
        params = kwargs.get('params', {})

        r = session.request(
            method.name,
            f"{self.endpoint}{path}",
            verify=(not self.allow_insecure),
            params=params,
            auth=auth,
            data=data,
            headers=request_headers,
            timeout=timeout
        )
        logger.info(f"call_api request {method.name} {self.endpoint}{path}, response: {r.status_code} {r.text[:1000]}")
        if raise_error:
            r.raise_for_status()
        return r

    def execute_benchmark_workload(self, workload: str,
                                   workload_params='bulk_size:10,bulk_indexing_clients:1'):
        client_options = "verify_certs:false"
        if not self.allow_insecure:
            client_options += ",use_ssl:true"
        password_to_censor = ""
        if self.auth_type == AuthMethod.BASIC_AUTH:
            auth_details = self.get_basic_auth_details()
            username = auth_details.username
            password_to_censor = auth_details.password
            client_options += (f",basic_auth_user:{username},"
                               f"basic_auth_password:{password_to_censor}")
        elif self.auth_type == AuthMethod.SIGV4:
            raise NotImplementedError(f"Auth type {self.auth_type} is not currently support for executing "
                                      f"benchmark workloads")
        logger.info(f"Running opensearch-benchmark with '{workload}' workload")
        command = (f"opensearch-benchmark run "
                   f"--exclude-tasks=check-cluster-health "
                   f"--target-host={self.endpoint} "
                   f"--workload={workload} "
                   f"--pipeline=benchmark-only "
                   "--test-mode --kill-running-processes "
                   f"--workload-params={workload_params} "
                   f"--client-options={client_options}")
        # While a little wordier, this approach prevents us from censoring the password if it appears in other contexts,
        # e.g. username:admin,password:admin.
        display_command = command.replace(f"basic_auth_password:{password_to_censor}", "basic_auth_password:********")
        logger.info(f"Executing command: {display_command}")
        subprocess.run(command, shell=True)

    def fetch_all_documents(self, index_name: str, batch_size: int = 100) -> Generator[Dict[str, Any], None, None]:
        """
        Generator that fetches all documents from the specified index in batches
        """

        session = requests.Session()

        # Step 1: Initiate the scroll
        path = f"/{index_name}/_search?scroll=1m"
        headers = {'Content-Type': 'application/json'}
        body = json.dumps({"size": batch_size, "query": {"match_all": {}}})
        response = self.call_api(
            path=path,
            method=HttpMethod.POST,
            data=body,
            headers=headers,
            session=session
        )

        response_json = response.json()
        scroll_id = response_json.get('_scroll_id')
        hits = response_json.get('hits', {}).get('hits', [])

        # Yield the first batch of documents
        if hits:
            yield {hit['_id']: hit['_source'] for hit in hits}

        # Step 2: Continue scrolling until no more documents
        while scroll_id and hits:
            path = "/_search/scroll"
            body = json.dumps({"scroll": "1m", "scroll_id": scroll_id})
            response = self.call_api(
                path=path,
                method=HttpMethod.POST,
                data=body,
                headers=headers,
                session=session
            )

            response_json = response.json()
            scroll_id = response_json.get('_scroll_id')
            hits = response_json.get('hits', {}).get('hits', [])

            if hits:
                yield {hit['_id']: hit['_source'] for hit in hits}

        # Step 3: Cleanup the scroll if necessary
        if scroll_id:
            path = "/_search/scroll"
            body = json.dumps({"scroll_id": scroll_id})
            self.call_api(
                path=path,
                method=HttpMethod.DELETE,
                data=body,
                headers=headers,
                session=session,
                raise_error=False
            )


class NoSourceClusterDefinedError(Exception):
    def __init__(self):
        super().__init__("Unable to continue without a source cluster specified")

    
class NoTargetClusterDefinedError(Exception):
    def __init__(self):
        super().__init__("Unable to continue without a target cluster specified")


class AuthModelType(str, Enum):
    no_auth = "no_auth"
    basic_auth_arn = "basic_auth_arn"
    sig_v4_auth = "sig_v4_auth"


class AuthBase(BaseModel):
    type: AuthModelType


class NoAuth(AuthBase):
    type: AuthModelType = AuthModelType.no_auth


class BasicAuthArn(AuthBase):
    type: AuthModelType = AuthModelType.basic_auth_arn
    user_secret_arn: str


class SigV4Auth(AuthBase):
    type: AuthModelType = AuthModelType.sig_v4_auth
    region: str
    service: str


AuthType: TypeAlias = NoAuth | BasicAuthArn | SigV4Auth


class ClusterInfo(BaseModel):
    endpoint: str
    protocol: str
    enable_tls_verification: bool
    auth: AuthType
    version_override: Optional[str] = None
