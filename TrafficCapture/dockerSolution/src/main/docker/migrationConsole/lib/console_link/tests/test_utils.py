from console_link.models.utils import append_user_agent_header_for_requests, create_boto3_client
import requests.utils

USER_AGENT_EXTRA = "test-user-agent-v1.0"


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
