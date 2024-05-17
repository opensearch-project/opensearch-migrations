import datetime
import random
import string
import json
from requests import Session


def create_index(endpoint: str, index_name: str, auth, verify_ssl: bool = False, session: Session = Session()):
    response = session.put(f'{endpoint}/{index_name}', auth=auth, verify=verify_ssl)

    return response


def check_index(endpoint: str, index_name: str, auth, verify_ssl: bool = False, session: Session = Session()):
    response = session.get(f'{endpoint}/{index_name}', auth=auth, verify=verify_ssl)

    return response


def delete_index(endpoint: str, index_name: str, auth, verify_ssl: bool = False, session: Session = Session()):
    response = session.delete(f'{endpoint}/{index_name}', auth=auth, verify=verify_ssl)

    return response


def delete_document(endpoint: str, index_name: str, doc_id: str, auth,
                    verify_ssl: bool = False, session: Session = Session()):
    response = session.delete(f'{endpoint}/{index_name}/_doc/{doc_id}', auth=auth, verify=verify_ssl)

    return response


def generate_large_doc(size_mib):
    # Calculate number of characters needed (1 char = 1 byte)
    num_chars = size_mib * 1024 * 1024

    # Generate random string of the desired length
    large_string = ''.join(random.choices(string.ascii_letters + string.digits, k=num_chars))

    return {
        "timestamp": datetime.datetime.now().isoformat(),
        "large_field": large_string
    }


def create_document(endpoint: str, index_name: str, doc_id: str, auth,
                    verify_ssl: bool = False, doc_body: dict = None, session: Session = Session()):
    if doc_body is None:
        document = {
            'title': 'Test Document',
            'content': 'This is a sample document for testing OpenSearch.'
        }
    else:
        document = doc_body

    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}
    response = session.put(url, headers=headers, data=json.dumps(document), auth=auth, verify=verify_ssl)

    return response


def get_document(endpoint: str, index_name: str, doc_id: str, auth,
                 verify_ssl: bool = False, session: Session = Session()):
    url = f'{endpoint}/{index_name}/_doc/{doc_id}'
    headers = {'Content-Type': 'application/json'}
    response = session.get(url, headers=headers, auth=auth, verify=verify_ssl)

    return response
