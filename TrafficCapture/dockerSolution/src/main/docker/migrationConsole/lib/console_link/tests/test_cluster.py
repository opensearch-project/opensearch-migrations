from base64 import b64encode
import os

import pytest
from moto import mock_aws
import boto3

import console_link.middleware.clusters as clusters_
from console_link.models.cluster import AuthMethod, Cluster
from tests.utils import create_valid_cluster


def test_valid_cluster_config():
    cluster = create_valid_cluster()
    assert isinstance(cluster, Cluster)


def test_invalid_auth_type_refused():
    invalid_auth_type = {
        "endpoint": "https://opensearchtarget:9200",
        "invalid_authorization": {},
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(invalid_auth_type)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["cluster"] == [
        "No values are present from set: ['basic_auth', 'no_auth', 'sigv4']",
        {'invalid_authorization': ['unknown field']}
    ]


def test_multiple_auth_types_refused():
    multiple_auth_types = {
        "endpoint": "https://opensearchtarget:9200",
        "basic_auth": {
            "username": "admin",
            "password": "myfakepassword"
        },
        "no_auth": {}
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(multiple_auth_types)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["cluster"] == [
        "More than one value is present: ['basic_auth', 'no_auth']"
    ]


def test_missing_auth_type_refused():
    missing_auth_type = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(missing_auth_type)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["cluster"] == [
        "No values are present from set: ['basic_auth', 'no_auth', 'sigv4']"
    ]


def test_missing_endpoint_refused():
    missing_endpoint = {
        "allow_insecure": True,
        "no_auth": {}
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(missing_endpoint)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]['cluster'][0]["endpoint"] == ["required field"]


def test_valid_cluster_with_secrets_auth_created():
    valid_with_secrets = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "basic_auth": {
            "username": "admin",
            "password_from_secret_arn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"
        },
    }
    cluster = Cluster(valid_with_secrets)
    assert isinstance(cluster, Cluster)


def test_invalid_cluster_with_two_passwords_refused():
    two_passwords = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "basic_auth": {
            "username": "XXXXX",
            "password": "XXXXXXXXXXXXXX",
            "password_from_secret_arn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"
        },
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(two_passwords)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["cluster"][0]['basic_auth'] == [
        "More than one value is present: ['password', 'password_from_secret_arn']"
    ]


def test_invalid_cluster_with_zero_passwords_refused():
    two_passwords = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "basic_auth": {
            "username": "XXXXX",
        },
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(two_passwords)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["cluster"][0]['basic_auth'] == [
        "No values are present from set: ['password', 'password_from_secret_arn']"
    ]


def test_valid_cluster_api_call_with_no_auth(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    assert isinstance(cluster, Cluster)

    requests_mock.get(f"{cluster.endpoint}/test_api", json={'test': True})
    response = cluster.call_api("/test_api")
    assert response.status_code == 200
    assert response.json() == {'test': True}


def test_connection_check_with_exception(mocker):
    cluster = create_valid_cluster()
    api_mock = mocker.patch.object(Cluster, 'call_api', side_effect=Exception('Attempt to connect to cluster failed'))

    result = clusters_.connection_check(cluster)
    api_mock.assert_called()
    assert 'Attempt to connect to cluster failed' in result.connection_message
    assert not result.connection_established


def test_connection_check_succesful(requests_mock):
    cluster = create_valid_cluster()
    requests_mock.get(f"{cluster.endpoint}/", json={'version': {'number': '2.15'}})

    result = clusters_.connection_check(cluster)
    assert result.connection_established
    assert result.connection_message == 'Successfully connected!'
    assert result.cluster_version == '2.15'


@pytest.fixture(scope="function")
def aws_credentials():
    """Mocked AWS Credentials for moto."""
    os.environ["AWS_ACCESS_KEY_ID"] = "testing"
    os.environ["AWS_SECRET_ACCESS_KEY"] = "testing"
    os.environ["AWS_SECURITY_TOKEN"] = "testing"
    os.environ["AWS_SESSION_TOKEN"] = "testing"
    os.environ["AWS_DEFAULT_REGION"] = "us-west-1"


def test_valid_cluster_api_call_with_secrets_auth(requests_mock, aws_credentials):
    valid_with_secrets = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "basic_auth": {
            "username": "admin",
            "password_from_secret_arn": None  # Will be set later
        },
    }
    secret_value = "myfakepassword"
    username = valid_with_secrets["basic_auth"]["username"]
    b64encoded_token = b64encode(f"{username}:{secret_value}".encode('utf-8')).decode("ascii")
    auth_header_should_be = f"Basic {b64encoded_token}"

    requests_mock.get(f"{valid_with_secrets['endpoint']}/test_api", json={'test': True})

    with mock_aws():
        # Create cluster
        secrets_client = boto3.client("secretsmanager")
        secret = secrets_client.create_secret(
            Name="test-cluster-password",
            SecretString=secret_value,
        )
        valid_with_secrets["basic_auth"]["password_from_secret_arn"] = secret['ARN']
        cluster = Cluster(valid_with_secrets)
        assert isinstance(cluster, Cluster)

        response = cluster.call_api("/test_api")
        assert response.status_code == 200
        assert response.json() == {'test': True}
        print(requests_mock.last_request.headers)
        assert requests_mock.last_request.headers['Authorization'] == auth_header_should_be
