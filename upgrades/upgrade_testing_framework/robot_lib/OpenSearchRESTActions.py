from pathlib import Path
import json
from typing import Any
import cluster_migration_core.core.versions_engine as ev
import cluster_migration_core.clients as clients
import os

DEFAULT_TEMP_STORAGE_DIR = "/tmp/utf"


class CouldNotRetrieveAttributeFromRESTResponseException(Exception):
    def __init__(self, key: str, response: dict):
        super().__init__(f"Could not find key '{key}' in the RESTResponse json: {response}")


class OpenSearchRESTActions(object):

    def __init__(self, engine_version: str, host="localhost", port=9200, temp_storage_directory=DEFAULT_TEMP_STORAGE_DIR):
        self._rest_client = clients.get_rest_client(ev.get_version(engine_version))
        self._temp_storage_directory = Path(temp_storage_directory)
        self._temp_storage_location = Path(os.path.join(temp_storage_directory, 'robot_data.json'))
        self._port = port

    def create_index(self, index_name: str):
        self._rest_client.create_an_index(port=self._port, index=index_name)

    def delete_index(self, index_name: str):
        self._rest_client.delete_an_index(port=self._port, index=index_name)

    def create_document(self, index_name: str, document: dict):
        self._rest_client.post_doc_to_index(port=self._port, index=index_name, doc=document)

    def count_documents_in_index(self, index_name: str) -> int:
        response = self._rest_client.count_docs_in_index(port=self._port, index=index_name).response_json
        count_key = "count"
        try:
            count = response[count_key]
        except KeyError:
            raise CouldNotRetrieveAttributeFromRESTResponseException(count_key, response)

        return count

    def refresh_index(self, index_name):
        self._rest_client.refresh_index(port=self._port, index=index_name)
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
            #This ensures the parent directory exists
            if not os.path.exists(self._temp_storage_directory):
                os.makedirs(self._temp_storage_directory)
            stored_data = {}
        stored_data[label] = data
        with self._temp_storage_location.open('w') as file_pointer:
            json.dump(stored_data, file_pointer)

    # Retrieve the stored data. If it doesn't exist, return None.
    def retrieve_stored_data_by_label(self, label: str) -> Any:
        with self._temp_storage_location.open('r') as file_pointer:
            stored_data = json.load(file_pointer)
        return stored_data.get(label)
