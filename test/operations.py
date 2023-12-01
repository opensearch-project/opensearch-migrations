import requests
import json
from typing import Optional, Tuple


def create_index(endpoint: str, index_name: str, auth: Optional[Tuple[str, str]] = None, verify_ssl: bool = False):
    response = requests.put(f'{endpoint}/{index_name}', auth=auth, verify=verify_ssl)

    return response


def check_index(endpoint: str, index_name: str, auth: Optional[Tuple[str, str]] = None, verify_ssl: bool = False):
    response = requests.get(f'{endpoint}/{index_name}', auth=auth, verify=verify_ssl)

    return response


def delete_index(endpoint: str, index_name: str, auth: Optional[Tuple[str, str]] = None, verify_ssl: bool = False):
    response = requests.delete(f'{endpoint}/{index_name}', auth=auth, verify=verify_ssl)

    return response


def delete_document(endpoint: str, index_name: str, doc_id: str, auth: Optional[Tuple[str, str]] = None,
                    verify_ssl: bool = False):
    response = requests.delete(f'{endpoint}/{index_name}/_doc/{doc_id}', auth=auth, verify=verify_ssl)

    return response


def create_document(endpoint: str, index_name: str, doc_id: str, auth: Optional[Tuple[str, str]] = None,
                    verify_ssl: bool = False):
    document = {
        'title': 'Test Document',
        'content': 'This is a sample document for testing OpenSearch.'
    }
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}

    response = requests.put(url, headers=headers, data=json.dumps(document), auth=auth, verify=verify_ssl)

    return response


def get_document(endpoint: str, index_name: str, doc_id: str, auth: Optional[Tuple[str, str]] = None,
                 verify_ssl: bool = False):
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}

    response = requests.get(url, headers=headers, auth=auth, verify=verify_ssl)

    return response
