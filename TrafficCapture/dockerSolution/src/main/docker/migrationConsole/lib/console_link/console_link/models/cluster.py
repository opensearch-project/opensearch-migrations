from typing import Dict, Optional
from enum import Enum
import requests
from requests.auth import HTTPBasicAuth
from cerberus import Validator

requests.packages.urllib3.disable_warnings()

AuthMethod = Enum("AuthMethod", ["NO_AUTH", "BASIC_AUTH", "SIGV4"])
HttpMethod = Enum("HttpMethod", ["GET", "POST", "PUT", "DELETE"])

SCHEMA = {
    'endpoint': {
        'type': 'string',
        'required': True
    },
    'authorization': {
        'type': 'dict',
        'required': True,
        'schema': {
            'type': {
                'type': 'string',
                'required': True,
                'allowed': [e.name.lower() for e in AuthMethod]
            },
            'details': {
                'type': 'dict',
                'required': False,
                'schema': {
                    'username': {
                        'type': 'string',
                        'required': False
                    },
                    'password': {
                        'type': 'string',
                        'required': False
                    },
                    'aws_secret_arn': {
                        'type': 'string',
                        'required': False
                    },
                }
            }
        }
    },
    'allow_insecure': {
        'type': 'boolean',
        'required': False
    },
}


class Cluster():
    """
    An elasticcsearch or opensearch cluster.
    """
    endpoint: str = ""
    aws_secret_arn: Optional[str] = None
    auth_type: Optional[AuthMethod] = None
    auth_details: Optional[Dict] = None

    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for cluster", v.errors)

        self.endpoint = config["endpoint"]
        if self.endpoint.startswith("https"):
            self.allow_insecure = config.get("allow_insecure", False)
        try:
            self.auth_type = AuthMethod[config["authorization"]["type"].upper()]
        except KeyError:
            self.auth_type = None
        try:
            self.auth_details = config["authorization"]["details"]
        except KeyError:
            self.auth_details = None
        try:
            self.aws_secret_arn = config["authorization"]["details"]["aws_secret_arn"]
        except KeyError:
            self.aws_secret_arn = None

    def call_api(self, path, method: HttpMethod = HttpMethod.GET) -> requests.Response:
        """
        Calls an API on the cluster.
        """
        if self.auth_type == AuthMethod.BASIC_AUTH:
            auth = HTTPBasicAuth(self.auth_details["username"], self.auth_details["password"])
        elif self.auth_type is None:
            auth = None
        else:
            raise NotImplementedError(f"Auth type {self.auth_type} not implemented")

        r = requests.request(method.name, f"{self.endpoint}{path}", verify=(not self.allow_insecure), auth=auth)
        r.raise_for_status()
        return r
