import logging
from requests import HTTPError, ConnectionError
from typing import Optional, Dict, Any

from transform_expert.utils.rest_client import RESTClient

logger = logging.getLogger("transform_expert")

class OpenSearchClient():
    rest_client: RESTClient

    def __init__(self, rest_client: RESTClient) -> None:
        self.rest_client = rest_client

    def get_url(self) -> str:
        return self.rest_client.base_url

    def is_accessible(self) -> bool:
        logger.info("Checking if OpenSearch Cluster is accessible")
        try:
            self.rest_client.get("")
            return True
        except (HTTPError, ConnectionError) as e:
            logger.error(f"OpenSearch Cluster is not accessible: {str(e)}")
            return False

    def create_index(self, index_name: str, settings: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        logger.info(f"Creating index: {index_name} with settings: {settings}")
        endpoint = f"{index_name}"
        return self.rest_client.put(endpoint, data=settings)

    def describe_index(self, index_name: str) -> Dict[str, Any]:
        logger.info(f"Describing index: {index_name}")
        endpoint = f"{index_name}"
        return self.rest_client.get(endpoint)

    def update_index(self, index_name: str, settings: Dict[str, Any]) -> Dict[str, Any]:
        logger.info(f"Updating index: {index_name} with settings: {settings}")
        endpoint = f"{index_name}/_settings"
        return self.rest_client.put(endpoint, data=settings)

    def delete_index(self, index_name: str) -> Dict[str, Any]:
        logger.info(f"Deleting index: {index_name}")
        endpoint = f"{index_name}"
        return self.rest_client.delete(endpoint)