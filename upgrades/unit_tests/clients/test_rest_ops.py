import json
import pytest
import unittest.mock as mock

import upgrade_testing_framework.clients.rest_ops as ops

REST_PATH = ops.RESTPath(9200, "http://gondolin", "turgon")


@pytest.fixture
def success_response():
    response = mock.Mock()
    response.json.return_value = {"key": "value"}
    response.status_code = 200
    response.reason = "fate"
    response.text = str(response.json())
    response.url = str(REST_PATH)
    return response


@pytest.fixture
def failure_response():
    response = mock.Mock()
    response.json.side_effect = json.JSONDecodeError("", "", 1)
    response.status_code = 404
    response.reason = "fate"
    response.text = "Not Found"
    response.url = str(REST_PATH)
    return response


@mock.patch("upgrade_testing_framework.clients.rest_ops.requests")
def test_WHEN_perform_get_AND_success_THEN_as_expected(mock_requests, success_response):
    # Set up test
    mock_requests.get.return_value = success_response

    # Run our test
    actual_value = ops.perform_get(rest_path=REST_PATH)

    # Check the results
    expected_value = {
        "response_json": success_response.json(),
        "response_text": success_response.text,
        "status_code": success_response.status_code,
        "status_reason": success_response.reason,
        "succeeded": True,
        "url": str(REST_PATH)
    }
    assert expected_value == actual_value.to_dict()


@mock.patch("upgrade_testing_framework.clients.rest_ops.requests")
def test_WHEN_perform_get_AND_failed_THEN_as_expected(mock_requests, failure_response):
    # Set up test
    mock_requests.get.return_value = failure_response

    # Run our test
    actual_value = ops.perform_get(rest_path=REST_PATH)

    # Check the results
    expected_value = {
        "response_json": None,
        "response_text": failure_response.text,
        "status_code": failure_response.status_code,
        "status_reason": failure_response.reason,
        "succeeded": False,
        "url": str(REST_PATH)
    }
    assert expected_value == actual_value.to_dict()


@mock.patch("upgrade_testing_framework.clients.rest_ops.requests")
def test_WHEN_perform_post_AND_success_THEN_as_expected(mock_requests, success_response):
    # Set up test
    mock_requests.post.return_value = success_response

    # Run our test
    actual_value = ops.perform_post(rest_path=REST_PATH)

    # Check the results
    expected_value = {
        "response_json": success_response.json(),
        "response_text": success_response.text,
        "status_code": success_response.status_code,
        "status_reason": success_response.reason,
        "succeeded": True,
        "url": str(REST_PATH)
    }
    assert expected_value == actual_value.to_dict()


@mock.patch("upgrade_testing_framework.clients.rest_ops.requests")
def test_WHEN_perform_put_AND_success_THEN_as_expected(mock_requests, success_response):
    # Set up test
    mock_requests.put.return_value = success_response

    # Run our test
    actual_value = ops.perform_put(rest_path=REST_PATH)

    # Check the results
    expected_value = {
        "response_json": success_response.json(),
        "response_text": success_response.text,
        "status_code": success_response.status_code,
        "status_reason": success_response.reason,
        "succeeded": True,
        "url": str(REST_PATH)
    }
    assert expected_value == actual_value.to_dict()
