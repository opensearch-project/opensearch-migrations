import datetime
import random
import string
import json
from requests import Session
import shlex
import subprocess
import logging

logger = logging.getLogger(__name__)


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


class ContainerNotFoundError(Exception):
    def __init__(self, container_filter):
        super().__init__(f"No containers matching the filter '{container_filter}' were found.")


def run_migration_console_command(deployment_type: str, command: str):
    if deployment_type == "local":
        filter_criteria = 'name=\"migration-console\"'
        cmd = f'docker ps --format=\"{{{{.ID}}}}\" --filter {filter_criteria}'

        get_container_process = subprocess.run(shlex.split(cmd), stdout=subprocess.PIPE, text=True)
        container_id = get_container_process.stdout.strip().replace('"', '')

        if container_id:
            cmd_exec = f"docker exec {container_id} bash -c '{command}'"
            logger.warning(f"Running command: {cmd_exec} on container {container_id}")
            process = subprocess.run(cmd_exec, shell=True, capture_output=True, text=True)
            return process.returncode, process.stdout, process.stderr
        else:
            raise ContainerNotFoundError(filter_criteria)

    else:
        # In a cloud deployment case, we run the e2e tests directly on the migration console, so it's just a local call
        logger.warning(f"Running command: {command} locally")
        process = subprocess.run(cmd_exec, shell=True, capture_output=True)
        return process.returncode, process.stdout, process.stderr
