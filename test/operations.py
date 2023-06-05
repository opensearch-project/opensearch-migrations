import requests
import json
import os


def create_index(endpoint, index_name, auth=None, data=None):
    response = requests.put(f'{endpoint}/{index_name}', auth=auth, verify=False)

    return response


def check_index(endpoint, index_name, auth=None, data=None):
    response = requests.get(f'{endpoint}/{index_name}', auth=auth, verify=False)

    return response


def delete_index(endpoint, index_name, auth=None, data=None):
    response = requests.delete(f'{endpoint}/{index_name}', auth=auth, verify=False)

    return response


def delete_document(endpoint, index_name, doc_id, auth=None, data=None):
    response = requests.delete(f'{endpoint}/{index_name}/_doc/{doc_id}', auth=auth, verify=False)

    return response


def create_document(endpoint, index_name, doc_id, auth=None):
    document = {
        'title': 'Test Document',
        'content': 'This is a sample document for testing OpenSearch.'
    }
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}

    response = requests.put(url, headers=headers, data=json.dumps(document), auth=auth, verify=False)

    return response


def get_document(endpoint, index_name, doc_id, auth=None):
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}

    response = requests.get(url, headers=headers, auth=auth, verify=False)

    return response


def main():

    username = os.getenv('USERNAME', 'admin')
    password = os.getenv('PASSWORD', 'admin')
    endpoint = os.getenv('ENDPOINT', 'https://localhost:9200')  # Dont forget port number.

    auth = (username, password)
    index = 'my_index'
    doc_id = '7'

    create_index(endpoint, index, auth)
    create_document(endpoint, index, doc_id, auth)
    delete_document(endpoint, index, doc_id, auth)
    delete_index(endpoint, index, auth)


if __name__ == "__main__":
    main()
