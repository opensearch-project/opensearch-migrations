from http import HTTPStatus
import requests
import json
import os


class Operations:
    @staticmethod
    def create_index(endpoint, index_name, auth=None, data=None):
        response = requests.put(f'{endpoint}/{index_name}', auth=auth)
        if response.status_code != HTTPStatus.OK:
            print('Failed to create index')
            print(response.text)
        else:
            print('Created index successfully')
            print(response.text)

        return response

    @staticmethod
    def check_index(endpoint, index_name, auth=None, data=None):
        response = requests.head(f'{endpoint}/{index_name}', auth=auth)
        if response.status_code != HTTPStatus.OK:
            print('Failed to create index')
            print(response.text)
        else:
            print('Created index successfully')
            print(response.text)

        return response

    @staticmethod
    def delete_index(endpoint, index_name, auth=None, data=None):
        response = requests.delete(f'{endpoint}/{index_name}', auth=auth)
        if response.status_code != HTTPStatus.OK:
            print('Failed to delete index')
            print(response.text)
        else:
            print('Deleted index successfully')
            print(response.text)

        return response

    @staticmethod
    def delete_document(endpoint, index_name, doc_id, auth=None, data=None):
        response = requests.delete(f'{endpoint}/{index_name}/_doc/{doc_id}', auth=auth)
        if response.status_code != HTTPStatus.OK:
            print('Failed to delete document')
            print(response.text)
        else:
            print('Deleted document successfully')
            print(response.text)

        return response

    @staticmethod
    def create_document(endpoint, index_name, doc_id, auth=None):
        document = {
            'title': 'Test Document',
            'content': 'This is a sample document for testing OpenSearch.'
        }
        url = f'{endpoint}/{index_name}/_doc/{doc_id}'
        headers = {'Content-Type': 'application/json'}

        response = requests.put(url, headers=headers, data=json.dumps(document), auth=auth)

        if response.status_code != HTTPStatus.CREATED:
            print('Failed to create document')
            print(response.text)
        else:
            print('Created document successfully')
            print(response.text)

        return response


def main():

    username = os.getenv('USERNAME', '')
    password = os.getenv('PASSWORD', '')
    endpoint = os.getenv('ENDPOINT')  # Dont forget port number.

    auth = (username, password)
    index = 'my_index'
    doc_id = '7'

    response1 = Operations.create_index(endpoint, index)
    response2 = Operations.create_document(endpoint, index, doc_id, auth)
    response3 = Operations.delete_document(endpoint, index, doc_id, auth)
    response4 = Operations.delete_index(endpoint, index)


if __name__ == "__main__":
    main()
