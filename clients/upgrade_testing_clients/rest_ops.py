from dataclasses import dataclass

import json
import logging
import requests
from requests.auth import HTTPBasicAuth

logger = logging.getLogger(__name__)

class RESTOperationFailedException(Exception):
    def __init__(self, operation: str, url: str, status_code: int, text: str):
        self.operation = operation
        self.url = url
        self.status_code = status_code
        self.text = text
        super().__init__(f"REST operation {operation} to {url} failed with code {status_code}.  Message: {text}")

@dataclass
class RESTPath:
    port: int = 9200
    prefix: str = "http://localhost"
    suffix: str = ""
    
    def __str__(self):
        # Something like: "http://localhost:9200/"
        base_path = f"{self.prefix}:{self.port}"
        return "/".join([base_path, self.suffix])

@dataclass
class RESTResponse:
    def __init__(self, response: requests.Response):
        self.response_text = response.text
        self.status_code = response.status_code
        self.status_reason = response.reason
        self.url = response.url

        self.response_json: dict = None
        try:
            self.response_json = response.json()
        except json.JSONDecodeError:
            pass

        self.succeeded = response.status_code in [200, 201]

    def to_dict(self) -> dict:
        return {
            "response_json": self.response_json,
            "response_text": self.response_text,
            "status_code": self.status_code,
            "status_reason": self.status_reason,
            "succeeded": self.succeeded,
            "url": self.url
        }

    def __str__(self):
        return json.dumps(self.to_dict())

def perform_get(rest_path: RESTPath = RESTPath(), params: dict = {}, auth = HTTPBasicAuth("admin", "admin")) -> RESTResponse:
    raw_reponse = requests.get(
        url=str(rest_path),
        auth=auth,
        params=params
    )

    rest_response = RESTResponse(raw_reponse)
    logger.debug(f"REST GET Response : {rest_response}")
    return rest_response

def perform_post(rest_path: RESTPath = RESTPath(), data: str = "", params: dict = {}, headers: dict = {}, 
        auth = HTTPBasicAuth("admin", "admin")) -> RESTResponse:
    raw_reponse = requests.post(
        url=str(rest_path),
        auth=auth,
        data=data,
        params=params,
        headers=headers
    )

    rest_response = RESTResponse(raw_reponse)
    logger.debug(f"REST POST Response : {rest_response}")
    return rest_response

def perform_put(rest_path: RESTPath = RESTPath(), data: str = "", params: dict = {}, headers: dict = {}, 
        auth = HTTPBasicAuth("admin", "admin")) -> RESTResponse:
    raw_reponse = requests.put(
        url=str(rest_path),
        auth=auth,
        data=data,
        params=params,
        headers=headers
    )

    rest_response = RESTResponse(raw_reponse)
    logger.debug(f"REST PUT Response : {rest_response}")
    return rest_response
