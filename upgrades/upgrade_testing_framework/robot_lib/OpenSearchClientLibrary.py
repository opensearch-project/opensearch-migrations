from pathlib import Path
import json
from typing import Any

from opensearchpy import OpenSearch

DEFAULT_TEMP_STORAGE = "/tmp/utf/robot_data.json"


# Change this library to use REST requests instead of Python client
class OpenSearchClientLibrary(object):

    def __init__(self, host="localhost", port=9200, temp_storage_location=DEFAULT_TEMP_STORAGE):
        os_client = OpenSearch(hosts=[{'host': host, 'port': port}])
        self._os_client = os_client
        self._temp_storage_location = Path(temp_storage_location)

    def create_index(self, index_name: str):
        self._os_client.indices.create(index_name)

    def create_document_in_index(self, index_name: str, document: str):
        self._os_client.index(index=index_name, body=document)

    def execute_query_on_index(self, index_name: str, query: str) -> str:
        return self._os_client.search(index=index_name, body=query)

    def count_documents_in_index(self, index_name: str) -> int:
        return self._os_client.count(index=index_name).get('count')

    def refresh_index(self, index_name):
        self._os_client.indices.refresh(index=index_name)

    # Storing intermediate state needs to be thought through, this uses a
    # a file to store state for time being. "Any" is a bit generous of a type
    # for data -- what we really mean is any json-serializable type.
    def store_data_with_label(self, data: Any, label: str):
        if self._temp_storage_location.exists():
            with self._temp_storage_location.open('r') as file_pointer:
                try:
                    stored_data = json.load(file_pointer)
                except json.JSONDecodeError:
                    # This is most likely an empty file.
                    stored_data = {}
        else:
            stored_data = {}
        stored_data[label] = data
        with self._temp_storage_location.open('w') as file_pointer:
            json.dump(stored_data, file_pointer)

    # Retrieve the stored data. If it doesn't exist, return None.
    def retrieve_stored_data_by_label(self, label: str) -> Any:
        with self._temp_storage_location.open('r') as file_pointer:
            stored_data = json.load(file_pointer)
        return stored_data.get(label)
