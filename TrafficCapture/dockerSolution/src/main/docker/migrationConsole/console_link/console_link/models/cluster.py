from typing import Dict, Optional
from enum import Enum
import requests
from requests.auth import HTTPBasicAuth

requests.packages.urllib3.disable_warnings() 

AuthMethod = Enum("AuthMethod", ["BASIC", "SIGV4"])
HttpMethod = Enum("HttpMethod", ["GET", "POST", "PUT", "DELETE"])


class Cluster():
    """
    An elasticcsearch or opensearch cluster.
    """
    endpoint: str = ""
    auth_type: Optional[AuthMethod] = None
    auth_details: Optional[Dict] = None

    def __init__(self, config: Dict) -> None:
        self.endpoint = config["endpoint"]
        if self.endpoint.startswith("https"):
            self.allow_insecure = config.get("allow_insecure", False)
        self.auth_type = AuthMethod[config["authorization"]["type"].upper()]
        self.auth_details = config["authorization"]["details"]
        pass

    def call_api(self, path, method: HttpMethod = HttpMethod.GET) -> Dict:
        """
        Calls an API on the cluster.
        """
        if self.auth_type == AuthMethod.BASIC:
            auth = HTTPBasicAuth(self.auth_details["username"], self.auth_details["password"])
        elif self.auth_type is None:
            auth = None
        else:
            raise NotImplementedError(f"Auth type {self.auth_type} not implemented")
        
        r = requests.request(method.name, f"{self.endpoint}{path}", verify=(not self.allow_insecure), auth=auth)
        r.raise_for_status()
        return r
