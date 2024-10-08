from base64 import b64encode
import os

import pytest
from moto import mock_aws
import boto3

import console_link.middleware.clusters as clusters_
from console_link.models.client_options import ClientOptions
from console_link.models.cluster import AuthMethod, Cluster
from tests.utils import create_valid_cluster


@pytest.fixture(scope="function")
def aws_credentials():
    """Mocked AWS Credentials for moto."""
    os.environ["AWS_ACCESS_KEY_ID"] = "testing"
    os.environ["AWS_SECRET_ACCESS_KEY"] = "testing"
    os.environ["AWS_SECURITY_TOKEN"] = "testing"
    os.environ["AWS_SESSION_TOKEN"] = "testing"
    os.environ["AWS_DEFAULT_REGION"] = "us-west-1"


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


def test_valid_cluster_with_sigv4_auth_created():
    valid_with_sigv4 = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "sigv4": {
            "region": "us-east-1",
            "service": "aoss"
        },
    }
    cluster = Cluster(valid_with_sigv4)
    assert isinstance(cluster, Cluster)
    assert cluster.auth_type == AuthMethod.SIGV4
    assert cluster._get_sigv4_details() == ("aoss", "us-east-1")


def test_valid_cluster_with_sigv4_auth_defaults_to_es_service():
    valid_with_sigv4 = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "sigv4": {
            "region": "us-east-1"
        }
    }
    cluster = Cluster(valid_with_sigv4)
    assert isinstance(cluster, Cluster)
    assert cluster.auth_type == AuthMethod.SIGV4
    assert cluster._get_sigv4_details() == ("es", "us-east-1")


def test_valid_cluster_with_sigv4_auth_uses_configured_region(aws_credentials):
    valid_with_sigv4 = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "sigv4": None
    }
    with mock_aws():
        cluster = Cluster(valid_with_sigv4)
        assert isinstance(cluster, Cluster)
        assert cluster.auth_type == AuthMethod.SIGV4
        assert cluster._get_sigv4_details() == ("es", None)
        assert cluster._get_sigv4_details(force_region=True) == ("es", "us-west-1")


def test_valid_cluster_with_sigv4_region_overrides_boto_region(aws_credentials):
    valid_with_sigv4 = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "sigv4": {
            "region": "eu-west-1"
        }
    }
    with mock_aws():
        cluster = Cluster(valid_with_sigv4)
        assert isinstance(cluster, Cluster)
        assert cluster.auth_type == AuthMethod.SIGV4
        assert cluster._get_sigv4_details() == ("es", "eu-west-1")
        assert cluster._get_sigv4_details(force_region=True) == ("es", "eu-west-1")


def test_valid_cluster_with_sigv4_region_doesnt_invoke_boto_client(mocker):
    # mock `boto3` to be null, which will cause all boto3 sessions in this model to fail
    mocker.patch("console_link.models.cluster.boto3", None)

    valid_with_sigv4 = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "sigv4": {
            "region": "eu-west-1"
        }
    }
    cluster = Cluster(valid_with_sigv4)
    assert isinstance(cluster, Cluster)
    assert cluster.auth_type == AuthMethod.SIGV4
    assert cluster._get_sigv4_details() == ("es", "eu-west-1")
    assert cluster._get_sigv4_details(force_region=True) == ("es", "eu-west-1")

    # For comparison, this one does try to invoke a boto client to get the region and fails
    valid_with_sigv4_no_region = {
        "endpoint": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
        "allow_insecure": True,
        "sigv4": None
    }
    with mock_aws():
        cluster = Cluster(valid_with_sigv4_no_region)
        assert isinstance(cluster, Cluster)
        assert cluster.auth_type == AuthMethod.SIGV4
        assert cluster._get_sigv4_details() == ("es", None)
        with pytest.raises(AttributeError):
            cluster._get_sigv4_details(force_region=True)


def test_valid_cluster_api_call_with_no_auth(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    assert isinstance(cluster, Cluster)

    requests_mock.get(f"{cluster.endpoint}/test_api", json={'test': True})
    response = cluster.call_api("/test_api")
    assert response.status_code == 200
    assert response.json() == {'test': True}


def test_valid_cluster_api_call_with_client_options(requests_mock):
    test_user_agent = "test-agent-v1.0"
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH,
                                   client_options=ClientOptions(config={"user_agent_extra": test_user_agent}))
    assert isinstance(cluster, Cluster)

    requests_mock.get(f"{cluster.endpoint}/test_api", json={'test': True})
    response = cluster.call_api("/test_api")
    assert response.headers == {}
    assert response.status_code == 200
    assert response.json() == {'test': True}

    assert test_user_agent in requests_mock.last_request.headers['User-Agent']


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
        assert requests_mock.last_request.headers['Authorization'] == auth_header_should_be


def test_valid_cluster_api_call_with_sigv4_auth(requests_mock, aws_credentials):
    valid_with_sigv4 = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "sigv4": {
            "region": "us-east-2",
            "service": "es"
        }
    }
    cluster = Cluster(valid_with_sigv4)

    requests_mock.get(f"{cluster.endpoint}/test_api", json={'test': True})

    with mock_aws():
        assert cluster.auth_type == AuthMethod.SIGV4
        auth_object = cluster._generate_auth_object()
        assert auth_object.service == "es"
        assert auth_object.region == "us-east-2"

        cluster.call_api("/test_api")
        auth_header = requests_mock.last_request.headers['Authorization']
        assert auth_header is not None
        assert "AWS4-HMAC-SHA256" in auth_header
        assert "SignedHeaders=" in auth_header
        assert "Signature=" in auth_header
        assert "es" in auth_header
        assert "us-east-2" in auth_header


def test_call_api_via_middleware(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    requests_mock.get(f"{cluster.endpoint}/test_api", json={'test': True})

    response = clusters_.call_api(cluster, '/test_api')
    assert response.status_code == 200
    assert response.json() == {'test': True}


def test_cat_indices_with_refresh(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    refresh_mock = requests_mock.get(f"{cluster.endpoint}/_refresh")
    indices_mock = requests_mock.get(f"{cluster.endpoint}/_cat/indices/_all")

    clusters_.cat_indices(cluster, refresh=True)
    assert refresh_mock.call_count == 1
    assert indices_mock.call_count == 1


def test_clear_indices(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    mock = requests_mock.delete(f"{cluster.endpoint}/*,-.*,-searchguard*,-sg7*,.migrations_working_state")
    clusters_.clear_indices(cluster)
    assert mock.call_count == 1


def test_run_benchmark_executes_correctly_no_auth(mocker):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    mock = mocker.patch("subprocess.run", autospec=True)
    workload = "nyctaxis"
    cluster.execute_benchmark_workload(workload=workload)
    mock.assert_called_once_with("opensearch-benchmark execute-test --distribution-version=1.0.0 "
                                 f"--target-host={cluster.endpoint} --workload={workload} --pipeline=benchmark-only"
                                 " --test-mode --kill-running-processes --workload-params=target_throughput:0.5,"
                                 "bulk_size:10,bulk_indexing_clients:1,search_clients:1 "
                                 "--client-options=verify_certs:false", shell=True)


def test_run_benchmark_raises_error_sigv4_auth():
    cluster = create_valid_cluster(auth_type=AuthMethod.SIGV4, details={"region": "eu-west-1", "service": "aoss"})
    workload = "nyctaxis"
    with pytest.raises(NotImplementedError):
        cluster.execute_benchmark_workload(workload=workload)


def test_run_benchmark_executes_correctly_basic_auth_and_https(mocker):
    auth_details = {"username": "admin", "password": "Admin1"}
    cluster = create_valid_cluster(auth_type=AuthMethod.BASIC_AUTH, details=auth_details)
    cluster.allow_insecure = False

    mock = mocker.patch("subprocess.run", autospec=True)
    workload = "nyctaxis"
    cluster.execute_benchmark_workload(workload=workload)
    mock.assert_called_once_with("opensearch-benchmark execute-test --distribution-version=1.0.0 "
                                 f"--target-host={cluster.endpoint} --workload={workload} --pipeline=benchmark-only"
                                 " --test-mode --kill-running-processes --workload-params=target_throughput:0.5,"
                                 "bulk_size:10,bulk_indexing_clients:1,search_clients:1 "
                                 "--client-options=verify_certs:false,use_ssl:true,"
                                 f"basic_auth_user:{auth_details['username']},"
                                 f"basic_auth_password:{auth_details['password']}", shell=True)
