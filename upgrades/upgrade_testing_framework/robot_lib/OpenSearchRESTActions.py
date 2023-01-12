from pathlib import Path
import json
from typing import Any
import cluster_migration_core.core.versions_engine as ev
import cluster_migration_core.clients as clients

DEFAULT_TEMP_STORAGE = "/tmp/utf/robot_data.json"


class CouldNotRetreiveAttributeFromRESTResponse(Exception):
    def __init__(self, key: str, response: dict):
        super().__init__(f"Could not find key '{key}' in the RESTResponse json: {response}")


class OpenSearchRESTActions(object):

    def __init__(self, host="localhost", port=9200, temp_storage_location=DEFAULT_TEMP_STORAGE):
        #This is temporary, this will be provided by the executor
        engine_version = ev.EngineVersion(ev.ENGINE_OPENSEARCH, 1, 3, 6)
        rest_client = clients.get_rest_client(engine_version)
        self._rest_client = rest_client
        self._temp_storage_location = Path(temp_storage_location)
        self._port = port

    def create_index(self, index_name: str):
        self._rest_client.create_an_index(port=self._port, index=index_name)

    def create_document(self, index_name: str, document: dict):
        self._rest_client.post_doc_to_index(port=self._port, index=index_name, doc=document)

    def count_documents_in_index(self, index_name: str) -> int:
        response = self._rest_client.count_doc_in_index(port=self._port, index=index_name).response_json
        count_key = "count"
        try:
            count = response[count_key]
            return count
        except CouldNotRetreiveAttributeFromRESTResponse(count_key, response):
            print('Please enter a valid attribute from RESTResponse')

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
            stored_data = {}
        stored_data[label] = data
        with self._temp_storage_location.open('w') as file_pointer:
            json.dump(stored_data, file_pointer)

    # Retrieve the stored data. If it doesn't exist, return None.
    def retrieve_stored_data_by_label(self, label: str) -> Any:
        with self._temp_storage_location.open('r') as file_pointer:
            stored_data = json.load(file_pointer)
        return stored_data.get(label)
