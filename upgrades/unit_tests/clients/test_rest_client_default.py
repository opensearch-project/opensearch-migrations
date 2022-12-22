import json
import unittest.mock as mock

from upgrade_testing_framework.clients.rest_ops import RESTPath
from upgrade_testing_framework.clients.rest_client_default import RESTClientDefault

TEST_DIR = "/path/to/dir"
TEST_DOC_ID = "Beren"
TEST_DOC = {"quote": "My fate, O King, led me hither, through perils such as few even of the Elves would dare."}
TEST_INDEX = "Silmarillion"
TEST_PORT = 9200
TEST_REPO = "repo"
TEST_SNAPSHOT_ID = "snapshot"

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_get")
def test_WHEN_get_node_info_THEN_as_expected(mock_get):
    # Set up test
    mock_response = mock.Mock()
    mock_get.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.get_node_info(TEST_PORT)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT)
    )]
    assert expected_calls == mock_get.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_get")
def test_WHEN_get_nodes_status_THEN_as_expected(mock_get):
    # Set up test
    mock_response = mock.Mock()
    mock_get.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.get_nodes_status(TEST_PORT)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = "_cat/nodes"),
        params = {"v": "true", "pretty": "true"}
    )]
    assert expected_calls == mock_get.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_get")
def test_WHEN_get_doc_by_id_THEN_as_expected(mock_get):
    # Set up test
    mock_response = mock.Mock()
    mock_get.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.get_doc_by_id(TEST_PORT, TEST_INDEX, TEST_DOC_ID)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"{TEST_INDEX}/_doc/{TEST_DOC_ID}"),
        params = {"pretty": "true"}
    )]
    assert expected_calls == mock_get.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_post")
def test_WHEN_post_doc_to_index_THEN_as_expected(mock_post):
    # Set up test
    mock_response = mock.Mock()
    mock_post.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.post_doc_to_index(TEST_PORT, TEST_INDEX, TEST_DOC)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"{TEST_INDEX}/_doc"),
        data = json.dumps(TEST_DOC),
        params = {"pretty": "true"},
        headers = {"Content-Type": "application/json"}
    )]
    assert expected_calls == mock_post.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_post")
def test_WHEN_create_snapshot_THEN_as_expected(mock_post):
    # Set up test
    mock_response = mock.Mock()
    mock_post.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.create_snapshot(TEST_PORT, TEST_REPO, TEST_SNAPSHOT_ID)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"_snapshot/{TEST_REPO}/{TEST_SNAPSHOT_ID}"),
        params = {"pretty": "true"}
    )]
    assert expected_calls == mock_post.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_get")
def test_WHEN_get_snapshot_by_id_THEN_as_expected(mock_get):
    # Set up test
    mock_response = mock.Mock()
    mock_get.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.get_snapshot_by_id(TEST_PORT, TEST_REPO, TEST_SNAPSHOT_ID)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"_snapshot/{TEST_REPO}/{TEST_SNAPSHOT_ID}"),
        params = {"pretty": "true"}
    )]
    assert expected_calls == mock_get.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_get")
def test_WHEN_get_snapshots_all_THEN_as_expected(mock_get):
    # Set up test
    mock_response = mock.Mock()
    mock_get.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.get_snapshots_all(TEST_PORT, TEST_REPO)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"_snapshot/{TEST_REPO}/_all"),
        params = {"pretty": "true"}
    )]
    assert expected_calls == mock_get.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_post")
def test_WHEN_register_snapshot_dir_THEN_as_expected(mock_post):
    # Set up test
    mock_response = mock.Mock()
    mock_post.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.register_snapshot_dir(TEST_PORT, TEST_REPO, TEST_DIR)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"_snapshot/{TEST_REPO}"),
        data = json.dumps({
            "type": "fs",
                "settings": {
                    "location": TEST_DIR
            }
        }),
        headers = {"Content-Type": "application/json"}
    )]
    assert expected_calls == mock_post.call_args_list

@mock.patch("upgrade_testing_framework.clients.rest_ops.perform_post")
def test_WHEN_restore_snapshot_THEN_as_expected(mock_post):
    # Set up test
    mock_response = mock.Mock()
    mock_post.return_value = mock_response

    # Run our test
    test_client = RESTClientDefault()
    actual_value = test_client.restore_snapshot(TEST_PORT, TEST_REPO, TEST_SNAPSHOT_ID)

    # Check the results
    assert mock_response == actual_value

    expected_calls = [mock.call(
        rest_path = RESTPath(port = TEST_PORT, suffix = f"_snapshot/{TEST_REPO}/{TEST_SNAPSHOT_ID}/_restore"),
        params = {"pretty": "true"}
    )]
    assert expected_calls == mock_post.call_args_list
