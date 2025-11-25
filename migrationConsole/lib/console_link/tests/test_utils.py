from console_link.models.client_options import ClientOptions
from console_link.models.utils import append_user_agent_header_for_requests, create_boto3_client
import requests.utils


USER_AGENT_EXTRA = "test-user-agent-v1.0"


def test_create_boto3_client_no_user_agent():
    client = create_boto3_client(aws_service_name="ecs")
    user_agent_for_client = client.meta.config.user_agent
    assert "Boto3" in user_agent_for_client


def test_create_boto3_client_with_user_agent():
    client_options = ClientOptions(config={"user_agent_extra": USER_AGENT_EXTRA})
    client = create_boto3_client(aws_service_name="ecs", client_options=client_options)
    user_agent_for_client = client.meta.config.user_agent
    assert "Boto3" in user_agent_for_client
    assert USER_AGENT_EXTRA in user_agent_for_client


def test_append_user_agent_header_for_requests_no_headers():
    expected_headers = {"User-Agent": f"{requests.utils.default_user_agent()} {USER_AGENT_EXTRA}"}
    result_headers = append_user_agent_header_for_requests(headers=None, user_agent_extra=USER_AGENT_EXTRA)
    assert result_headers == expected_headers


def test_append_user_agent_header_for_requests_existing_headers():
    existing_headers = {"Accept": "/*", "Host": "macosx"}
    expected_headers = dict(existing_headers)
    expected_headers["User-Agent"] = f"{requests.utils.default_user_agent()} {USER_AGENT_EXTRA}"
    result_headers = append_user_agent_header_for_requests(headers=existing_headers, user_agent_extra=USER_AGENT_EXTRA)
    assert result_headers == expected_headers


def test_append_user_agent_header_for_requests_existing_headers_with_user_agent():
    existing_headers = {"Accept": "/*", "Host": "macosx", "User-Agent": "pyclient"}
    expected_headers = dict(existing_headers)
    expected_headers["User-Agent"] = f"pyclient {USER_AGENT_EXTRA}"
    result_headers = append_user_agent_header_for_requests(headers=existing_headers, user_agent_extra=USER_AGENT_EXTRA)
    assert result_headers == expected_headers
