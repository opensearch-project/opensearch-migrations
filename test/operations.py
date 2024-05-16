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


def create_document(endpoint: str, index_name: str, doc_id: str, auth,
                    verify_ssl: bool = False, session: Session = Session()):
    document = {
        'title': 'Test Document',
        'content': 'This is a sample document for testing OpenSearch.'
    }
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
