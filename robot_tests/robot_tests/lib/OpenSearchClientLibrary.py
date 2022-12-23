from opensearchpy import OpenSearch

# Remove opensearch python client functions here in favor of shared clients package
class OpenSearchClientLibrary(object):

    def __init__(self, host="localhost", port=9200):
        os_client = OpenSearch(hosts=[{'host': host, 'port': port}])
        self._os_client = os_client

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
    # a file to store state for time being
    def store_data_with_label(self, data: str, label: str):
        file = open('temp-storage.txt', 'a')
        file.write(label + "=" + str(data) + "\n")
        file.close()

    def retrieve_stored_number_by_label(self, label: str) -> int:
        file = open('temp-storage.txt', 'r')
        for line in file:
            if line.startswith(label + "="):
                return int(line.split("=", 1)[1])
        return -1
