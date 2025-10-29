import boto3
import hashlib
import os
import pytest
import re
import json
import datetime
from unittest.mock import patch

from base64 import b64encode
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from console_link.models.client_options import ClientOptions
from console_link.models.cluster import AuthMethod, Cluster, HttpMethod
from moto import mock_aws
from tests.utils import create_valid_cluster
import requests


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
            "user_secret_arn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"
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
            "user_secret_arn": "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"
        },
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(two_passwords)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["cluster"][0]['basic_auth'] == [
        "Cannot provide both (username + password) and user_secret_arn"
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
        "Must provide either (username + password) or user_secret_arn"
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


def test_valid_cluster_fetch_all_documents(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    assert isinstance(cluster, Cluster)

    test_index = "test_index"
    batch_size = 1
    test_scroll_id = "test_scroll_id"
    requests_mock.post(
        f"{cluster.endpoint}/{test_index}/_search?scroll=1m",
        json={
            "_scroll_id": test_scroll_id,
            "hits": {
                "hits": [{"_id": "id_1", "_source": {"test1": True}}]
            }
        }
    )
    requests_mock.post(
        f"{cluster.endpoint}/_search/scroll",
        json={
            "_scroll_id": None,
            "hits": {
                "hits": [{"_id": "id_2", "_source": {"test2": True}}]
            }
        }
    )
    requests_mock.delete(f"{cluster.endpoint}/_search/scroll")
    documents = [batch for batch in cluster.fetch_all_documents(test_index, batch_size=batch_size)]
    assert documents == [{"id_1": {"test1": True}}, {"id_2": {"test2": True}}]


def test_valid_cluster_api_call_with_secrets_auth(requests_mock, aws_credentials):
    valid_with_secrets = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "basic_auth": {
            "user_secret_arn": None  # Will be set later
        },
    }
    username = 'unit_test_user'
    password = 'unit_test_pass'
    secret_value = f"{{\"username\": \"{username}\", \"password\": \"{password}\"}}"
    b64encoded_token = b64encode(f"{username}:{password}".encode('utf-8')).decode("ascii")
    auth_header_should_be = f"Basic {b64encoded_token}"

    requests_mock.get(f"{valid_with_secrets['endpoint']}/test_api", json={'test': True})

    with mock_aws():
        secrets_client = boto3.client("secretsmanager")
        secret = secrets_client.create_secret(
            Name="test-cluster-password",
            SecretString=secret_value,
        )
        valid_with_secrets["basic_auth"]["user_secret_arn"] = secret['ARN']
        cluster = Cluster(valid_with_secrets)
        assert isinstance(cluster, Cluster)

        response = cluster.call_api("/test_api")
        assert response.status_code == 200
        assert response.json() == {'test': True}
        assert requests_mock.last_request.headers['Authorization'] == auth_header_should_be


def test_valid_cluster_api_call_with_sigv4_auth(requests_mock, aws_credentials):
    valid_with_sigv4 = {
        "endpoint": "https://test.opensearchtarget.com:9200",
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
        host_header = requests_mock.last_request.headers['Host']
        assert "test.opensearchtarget.com" == host_header


def test_run_benchmark_executes_correctly_no_auth(mocker):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    mock = mocker.patch("subprocess.run", autospec=True)
    workload = "nyctaxis"
    cluster.execute_benchmark_workload(workload=workload)
    mock.assert_called_once_with("opensearch-benchmark run"
                                 " --exclude-tasks=check-cluster-health"
                                 f" --target-host={cluster.endpoint} --workload={workload}"
                                 " --pipeline=benchmark-only"
                                 " --test-mode --kill-running-processes --workload-params="
                                 "bulk_size:10,bulk_indexing_clients:1 "
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
    mock.assert_called_once_with("opensearch-benchmark run"
                                 " --exclude-tasks=check-cluster-health"
                                 f" --target-host={cluster.endpoint} --workload={workload}"
                                 " --pipeline=benchmark-only"
                                 " --test-mode --kill-running-processes --workload-params="
                                 "bulk_size:10,bulk_indexing_clients:1 "
                                 "--client-options=verify_certs:false,use_ssl:true,"
                                 f"basic_auth_user:{auth_details['username']},"
                                 f"basic_auth_password:{auth_details['password']}", shell=True)


@pytest.mark.parametrize("method, endpoint, data, has_body", [
    (HttpMethod.GET, "/_cluster/health", None, False),
    (HttpMethod.POST, "/_search", {"query": {"match_all": {}}}, True)
])
def test_sigv4_authentication_signature(requests_mock, method, endpoint, data, has_body):
    # Set up a valid cluster configuration with SIGV4 authentication
    sigv4_cluster_config = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "sigv4": {
            "region": "us-east-1",
            "service": "es"
        }
    }
    cluster = Cluster(sigv4_cluster_config)

    # Prepare the mocked API response
    url = f"{cluster.endpoint}{endpoint}"
    if method == HttpMethod.GET:
        requests_mock.get(url, json={'status': 'green'})
    elif method == HttpMethod.POST:
        requests_mock.post(url, json={'hits': {'total': 0, 'hits': []}})
    # Mock datetime to return a specific timestamp
    specific_time = datetime.datetime(2025, 1, 1, 12, 0, 0)
    # Patch botocore's datetime usage instead of the datetime module itself
    with mock_aws(), patch('botocore.auth.datetime') as mock_datetime:
        mock_datetime.datetime.utcnow.return_value = specific_time
        mock_datetime.datetime.now.return_value = specific_time
        mock_datetime.datetime.strftime = datetime.datetime.strftime

        # Add default headers to the request
        headers = {
            # These headers are excluded from signing since they are in default request headers
            'User-Agent': 'my-test-agent',
            'Accept': 'application/json',
            'Accept-Encoding': 'gzip, deflate',
            'Connection': 'keep-alive',
            # Custom headers to be included in the request
            'X-Custom-Header-1': 'CustomValue1',
            'X-Custom-Header-2': 'CustomValue2'
        }
        if data is not None:
            response = cluster.call_api(endpoint, method=method, data=json.dumps(data), headers=headers)
        else:
            response = cluster.call_api(endpoint, method=method, headers=headers)
        assert response.status_code == 200

        # Retrieve the last request made
        last_request = requests_mock.last_request

        # Verify the default headers are present in the request headers
        for header_name, header_value in headers.items():
            assert last_request.headers.get(header_name) == header_value

        # Verify the Authorization header
        auth_header = last_request.headers.get('Authorization')
        assert auth_header is not None, "Authorization header is missing"
        assert auth_header.startswith("AWS4-HMAC-SHA256"), "Incorrect Authorization header format"

        # Extract SignedHeaders and Signature from the Authorization header
        signed_headers_match = re.search(r"SignedHeaders=([^,]+)", auth_header)
        signature_match = re.search(r"Signature=([a-f0-9]+)", auth_header)
        assert signed_headers_match is not None, "SignedHeaders not found in Authorization header"
        assert signature_match is not None, "Signature not found in Authorization header"

        # Verify that default headers are not included in SignedHeaders
        signed_headers = signed_headers_match.group(1).split(';')
        default_headers = [header.lower() for header in headers.keys() if header.lower()
                           in requests.utils.default_headers().keys()]
        assert len(default_headers) > 0, "Default headers should contain at least one header"
        for header in default_headers:
            assert header not in signed_headers, f"Default header '{header}' should not be included in SignedHeaders"

        # Verify that essential headers are included in SignedHeaders
        required_headers = ['host', 'x-amz-date', 'x-custom-header-1', 'x-custom-header-2']
        for header in required_headers:
            assert header in signed_headers, f"Header '{header}' not found in SignedHeaders," + \
                f" actual headers: {signed_headers}"

        # Check that the x-amz-date header is present
        amz_date_header = last_request.headers.get('x-amz-date')
        assert amz_date_header is not None, "x-amz-date header is missing"

        if has_body:
            # Verify that the 'x-amz-content-sha256' header is present
            content_sha256 = last_request.headers.get('x-amz-content-sha256')
            assert content_sha256 is not None, 'x-amz-content-sha256 header is missing'
            # Compute the SHA256 hash of the body
            body_hash = hashlib.sha256(last_request.body.encode('utf-8')).hexdigest()
            assert content_sha256 == body_hash, "x-amz-content-sha256 does not match body hash"

        # Re-sign the request using botocore to verify the signature
        session = boto3.Session()
        credentials = session.get_credentials()
        service_name = cluster.auth_details.get("service", "es")
        region_name = cluster.auth_details.get("region", "us-east-1")
        # Create a new AWSRequest
        aws_request = AWSRequest(
            method=last_request.method,
            url=last_request.url,
            data=last_request.body,
            headers={k: v for k, v in last_request.headers.items() if
                     k.lower() not in requests.utils.default_headers().keys()}
        )
        # Sign the request
        SigV4Auth(credentials, service_name, region_name).add_auth(aws_request)

        # Extract the new signature
        new_auth_header = aws_request.headers.get('Authorization')
        assert new_auth_header is not None, "Failed to generate new Authorization header"

        # Compare timestamp
        assert amz_date_header == aws_request.headers.get("x-amz-date")
        # Compare signatures
        original_signature = signature_match.group(1)
        new_signature_match = re.search(r"Signature=([a-f0-9]+)", new_auth_header)
        assert new_signature_match is not None, "New signature not found in Authorization header"
        new_signature = new_signature_match.group(1)

        assert original_signature == new_signature, "Signatures do not match"


def test_valid_basic_auth_secret(mocker):
    mock_client = mocker.Mock()
    mock_client.get_secret_value.return_value = {
        "SecretString": '{"username": "admin", "password": "pass123!"}'
    }
    mocker.patch("console_link.models.cluster.create_boto3_client", return_value=mock_client)
    secret_arn = "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"

    cluster_config = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "basic_auth": {
            "user_secret_arn": secret_arn
        },
    }
    cluster = Cluster(cluster_config)
    auth_details = cluster.get_basic_auth_details()
    mock_client.get_secret_value.assert_called_once_with(
        SecretId=secret_arn
    )
    assert mock_client.get_secret_value.call_count == 1
    assert auth_details.username == "admin"
    assert auth_details.password == "pass123!"


def test_invalid_basic_auth_secret_no_json(mocker):
    mock_client = mocker.Mock()
    mock_client.get_secret_value.return_value = {
        "SecretString": "pass123!"
    }
    mocker.patch("console_link.models.cluster.create_boto3_client", return_value=mock_client)
    secret_arn = "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"

    cluster_config = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "basic_auth": {
            "user_secret_arn": secret_arn
        },
    }
    cluster = Cluster(cluster_config)
    with pytest.raises(ValueError) as exc_info:
        cluster.get_basic_auth_details()
    assert str(exc_info.value) == (f"Expected secret {secret_arn} to be a JSON object with username "
                                   f"and password fields")
    mock_client.get_secret_value.assert_called_once_with(SecretId=secret_arn)


def test_invalid_basic_auth_secret_improper_fields(mocker):
    mock_client = mocker.Mock()
    mock_client.get_secret_value.return_value = {
        "SecretString": '{"user": "admin", "pass": "pass123!"}'
    }
    mocker.patch("console_link.models.cluster.create_boto3_client", return_value=mock_client)
    secret_arn = "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass"

    cluster_config = {
        "endpoint": "https://opensearchtarget:9200",
        "allow_insecure": True,
        "basic_auth": {
            "user_secret_arn": secret_arn
        },
    }
    cluster = Cluster(cluster_config)
    with pytest.raises(ValueError) as exc_info:
        cluster.get_basic_auth_details()
    assert str(exc_info.value) == (f"Secret {secret_arn} is missing required key(s): username, password")
    mock_client.get_secret_value.assert_called_once_with(SecretId=secret_arn)
