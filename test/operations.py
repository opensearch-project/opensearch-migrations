import requests
import json
from typing import Optional, Tuple


def create_index(endpoint: str, index_name: str, auth: Optional[Tuple[str, str]] = None):
    response = requests.put(f'{endpoint}/{index_name}', auth=auth, verify=False)

    return response


def check_index(endpoint: str, index_name: str, auth: Optional[Tuple[str, str]] = None):
    response = requests.get(f'{endpoint}/{index_name}', auth=auth, verify=False)

    return response


def delete_index(endpoint: str, index_name: str, auth: Optional[Tuple[str, str]] = None):
    response = requests.delete(f'{endpoint}/{index_name}', auth=auth, verify=False)

    return response


def delete_document(endpoint: str, index_name: str, doc_id: str, auth: Optional[Tuple[str, str]] = None):
    response = requests.delete(f'{endpoint}/{index_name}/_doc/{doc_id}', auth=auth, verify=False)

    return response


def create_document(endpoint: str, index_name: str, doc_id: str, auth: Optional[Tuple[str, str]] = None):
    document = {
        'title': 'Test Document',
        'content': 'This is a sample document for testing OpenSearch.'
    }
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}

    response = requests.put(url, headers=headers, data=json.dumps(document), auth=auth, verify=False)

    return response


def check_document(endpoint: str, index_name: str, doc_id: str, auth: Optional[Tuple[str, str]] = None):
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}

    response = requests.get(url, headers=headers, auth=auth, verify=False)

    return response
