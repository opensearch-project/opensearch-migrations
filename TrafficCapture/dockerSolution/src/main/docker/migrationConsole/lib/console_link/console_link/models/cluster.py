from typing import Any, Dict, Optional
from enum import Enum
import requests
from requests.auth import HTTPBasicAuth
from cerberus import Validator
import logging
import subprocess
from console_link.models.schema_tools import contains_one_of

requests.packages.urllib3.disable_warnings()  # ignore: type

logger = logging.getLogger(__name__)

AuthMethod = Enum("AuthMethod", ["NO_AUTH", "BASIC_AUTH", "SIGV4"])
HttpMethod = Enum("HttpMethod", ["GET", "POST", "PUT", "DELETE"])


NO_AUTH_SCHEMA = {
    "nullable": True,
}

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
            "excludes": ["aws_secret_arn"]
        },
        "aws_secret_arn": {
            "type": "string",
            "required": False,
            "excludes": ["password"]
        }
    },
}

SIGV4_SCHEMA = {
    "nullable": True,
}

SCHEMA = {
    "cluster": {
        "type": "dict",
        "schema": {
            "endpoint": {"type": "string", "required": True},
            "allow_insecure": {"type": "boolean", "required": False},
            "no_auth": NO_AUTH_SCHEMA,
            "basic_auth": BASIC_AUTH_SCHEMA,
            "sigv4": SIGV4_SCHEMA
        },
        "check_with": contains_one_of({auth.name.lower() for auth in AuthMethod})
    }
}


class Cluster:
    """
    An elasticcsearch or opensearch cluster.
    """

    endpoint: str = ""
    aws_secret_arn: Optional[str] = None
    auth_type: Optional[AuthMethod] = None
    auth_details: Optional[Dict[str, Any]] = None
    allow_insecure: bool = False

    def __init__(self, config: Dict) -> None:
        logger.info(f"Initializing cluster with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate({'cluster': config}):
            raise ValueError("Invalid config file for cluster", v.errors)

        self.endpoint = config["endpoint"]
        self.allow_insecure = config.get("allow_insecure", False)
        if 'no_auth' in config:
            self.auth_type = AuthMethod.NO_AUTH
        elif 'basic_auth' in config:
            self.auth_type = AuthMethod.BASIC_AUTH
            self.auth_details = config["basic_auth"]
        elif 'sigv4' in config:
            self.auth_type = AuthMethod.SIGV4

    def call_api(self, path, method: HttpMethod = HttpMethod.GET, timeout=None, json_body=None) -> requests.Response:
        """
        Calls an API on the cluster.
        """
        if self.auth_type == AuthMethod.BASIC_AUTH:
            assert self.auth_details is not None  # for mypy's sake
            auth = HTTPBasicAuth(
                self.auth_details.get("username", None),
                self.auth_details.get("password", None)
            )
        elif self.auth_type is AuthMethod.NO_AUTH:
            auth = None
        else:
            raise NotImplementedError(f"Auth type {self.auth_type} not implemented")

        if json_body is not None:
            # headers = {'Content-type': 'application/json'}
            data = json_body
        else:
            # headers = None
            data = None

        logger.info(f"Making api call to {self.endpoint}{path}")
        r = requests.request(
            method.name,
            f"{self.endpoint}{path}",
            verify=(not self.allow_insecure),
            auth=auth,
            timeout=timeout,
            json=data
        )
        logger.debug(f"Cluster API call request: {r.request}")
        r.raise_for_status()
        return r

    def execute_benchmark_workload(self, workload: str,
                                   workload_params='target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,'
                                                   'search_clients:1'):
        client_options = ""
        if not self.allow_insecure:
            client_options += "use_ssl:true,verify_certs:false"
        if self.auth_type == AuthMethod.BASIC_AUTH:
            if self.auth_details['password'] is not None:
                client_options += (f"basic_auth_user:{self.auth_details['username']},"
                                   f"basic_auth_password:{self.auth_details['password']}")
            else:
                raise NotImplementedError(f"Auth type {self.auth_type} with AWS Secret ARN is not currently support "
                                          f"for executing benchmark workloads")
        elif self.auth_type == AuthMethod.SIGV4:
            raise NotImplementedError(f"Auth type {self.auth_type} is not currently support for executing "
                                      f"benchmark workloads")
        # Note -- we should censor the password when logging this command
        logger.info(f"Running opensearch-benchmark with '{workload}' workload")
        subprocess.run(f"opensearch-benchmark execute-test --distribution-version=1.0.0 "
                       f"--target-host={self.endpoint} --workload={workload} --pipeline=benchmark-only --test-mode "
                       f"--kill-running-processes --workload-params={workload_params} "
                       f"--client-options={client_options}", shell=True)
