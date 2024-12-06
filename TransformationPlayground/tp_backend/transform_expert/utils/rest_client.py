from dataclasses import dataclass
import requests
import logging
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)

@dataclass
class ConnectionDetails:
    base_url: str

# Raw REST client responsible for making HTTP requests to the OpenSearch cluster
class RESTClient():
    def __init__(self, connection_details: ConnectionDetails) -> None:
        self.base_url = connection_details.base_url.rstrip('/')

    def get(self, endpoint: str) -> Dict[str, Any]:
        url = f"{self.base_url}/{endpoint}"
        logger.debug(f"GET request to URL: {url}")
        response = requests.get(url)
        response.raise_for_status()
        logger.debug(f"GET response: {response.status_code} {response.text}")
        return response.json()

    def put(self, endpoint: str, data: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        url = f"{self.base_url}/{endpoint}"
        logger.debug(f"PUT request to URL: {url} with data: {data}")
        response = requests.put(url, json=data)
        response.raise_for_status()
        logger.debug(f"PUT response: {response.status_code} {response.text}")
        return response.json()

    def post(self, endpoint: str, data: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        url = f"{self.base_url}/{endpoint}"
        logger.debug(f"POST request to URL: {url} with data: {data}")
        response = requests.post(url, json=data)
        response.raise_for_status()
        logger.debug(f"POST response: {response.status_code} {response.text}")
        return response.json()

    def delete(self, endpoint: str) -> Dict[str, Any]:
        url = f"{self.base_url}/{endpoint}"
        logger.debug(f"DELETE request to URL: {url}")
        response = requests.delete(url)
        response.raise_for_status()
        logger.debug(f"DELETE response: {response.status_code} {response.text}")
        return response.json()

