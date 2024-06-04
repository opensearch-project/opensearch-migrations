from typing import Any, Dict, Optional
from enum import Enum
import requests
from requests.auth import HTTPBasicAuth
from cerberus import Validator
import logging

requests.packages.urllib3.disable_warnings()  # ignore: type

logger = logging.getLogger(__name__)

AuthMethod = Enum("AuthMethod", ["NO_AUTH", "BASIC_AUTH", "SIGV4"])
HttpMethod = Enum("HttpMethod", ["GET", "POST", "PUT", "DELETE"])

SCHEMA = {
    "endpoint": {"type": "string", "required": True},
    "allow_insecure": {"type": "boolean", "required": False},
    "authorization": {
        "type": "dict",
        "required": True,
        "schema": {
            "type": {
                "type": "string",
                "required": True,
                "allowed": [e.name.lower() for e in AuthMethod]
            },
            "details": {
                "type": "dict",
                "required": False,
                "schema": {
                    "username": {
                        "type": "string",
                        "required": False
                    },
                    "password": {
                        "type": "string",
                        "required": False
                    },
                    "aws_secret_arn": {
                        "type": "string",
                        "required": False
                    },
                }
            }
        }
    },
}


class Cluster:
    """
    An elasticcsearch or opensearch cluster.
    """

    endpoint: str = ""
    aws_secret_arn: Optional[str] = None
    auth_type: Optional[AuthMethod] = None
    auth_details: Optional[Dict[str, Any]] = None

    def __init__(self, config: Dict) -> None:
        logger.info(f"Initializing cluster with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for cluster", v.errors)

        self.endpoint = config["endpoint"]
        if self.endpoint.startswith("https"):
            self.allow_insecure = config.get("allow_insecure", False)
        self.auth_type = AuthMethod[config["authorization"]["type"].upper()]
        self.auth_details = config["authorization"].get("details", None)
        self.aws_secret_arn = None if self.auth_details is None else self.auth_details.get("aws_secret_arn", None)

    def call_api(self, path, method: HttpMethod = HttpMethod.GET) -> requests.Response:
        """
        Calls an API on the cluster.
        """
        if self.auth_type == AuthMethod.BASIC_AUTH:
            assert self.auth_details is not None  # for mypy's sake
            auth = HTTPBasicAuth(
                self.auth_details.get("username", None),
                self.auth_details.get("password", None)
            )
        elif self.auth_type is None:
            auth = None
        else:
            raise NotImplementedError(f"Auth type {self.auth_type} not implemented")

        logger.info(f"Making api call to {self.endpoint}{path}")
        r = requests.request(
            method.name,
            f"{self.endpoint}{path}",
            verify=(not self.allow_insecure),
            auth=auth,
        )
        logger.debug(f"Cluster API call request: {r.request}")
        r.raise_for_status()
        return r
