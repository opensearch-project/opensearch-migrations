import console_link.middleware.clusters as clusters_

import unittest.mock as mock

from console_link.models.cluster import AuthMethod, Cluster, HttpMethod
from tests.utils import create_valid_cluster


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


def test_cat_indices_with_refresh(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    refresh_mock = requests_mock.get(f"{cluster.endpoint}/_refresh")
    indices_mock = requests_mock.get(f"{cluster.endpoint}/_cat/indices/_all")

    clusters_.cat_indices(cluster, refresh=True)
    assert refresh_mock.call_count == 1
    assert indices_mock.call_count == 1


def test_cat_indices_with_error_prints_cleanly():
    cluster = mock.Mock(spec=Cluster)
    error_message = "Secret has improper JSON structure"
    cluster.call_api.side_effect = ValueError(error_message)

    response_str = clusters_.cat_indices(cluster, refresh=False)
    cluster.call_api.assert_called_once()
    assert response_str == f"Error: Unable to perform cat-indices command with message: {error_message}"


def test_clear_indices(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    mock = requests_mock.delete(f"{cluster.endpoint}/*,-.*,-searchguard*,-sg7*,.migrations_working_state*")
    clusters_.clear_indices(cluster)
    assert mock.call_count == 1


def test_call_api_via_middleware(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    requests_mock.get(f"{cluster.endpoint}/test_api", json={'test': True})

    result = clusters_.call_api(cluster, '/test_api')
    response = result.http_response
    assert response.status_code == 200
    assert response.json() == {'test': True}


def test_call_api_with_head_method(requests_mock):
    cluster = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    requests_mock.head(f"{cluster.endpoint}/test_api")

    result = clusters_.call_api(cluster, '/test_api', HttpMethod.HEAD)
    response = result.http_response
    assert response.status_code == 200


def test_call_api_with_error_prints_cleanly():
    cluster = mock.Mock(spec=Cluster)
    error_message = "Secret has improper JSON structure"
    cluster.call_api.side_effect = ValueError(error_message)

    result = clusters_.call_api(cluster, "/test_api")
    cluster.call_api.assert_called_once()
    assert result.error_message == f"Error: Unable to perform cluster command with message: {error_message}"


def test_call_api_timeout_returns_friendly_message():
    import requests.exceptions
    cluster = mock.Mock(spec=Cluster)
    cluster.call_api.side_effect = requests.exceptions.ConnectTimeout(
        "HTTPSConnectionPool(host='example.com', port=443): Connect timed out. (connect timeout=15)")

    result = clusters_.call_api(cluster, "/test_api", timeout=15)
    assert result.http_response is None
    assert "timed out" in result.error_message
    assert "15s" in result.error_message
    assert "--timeout" in result.error_message
    assert "HTTPSConnectionPool" not in result.error_message
