from unittest.mock import Mock

import pytest
import requests

from console_link.workflow.web.server import (
    cloudwatch_log_group_url,
    load_workflow_for_approvals,
)


def _http_error(status_code):
    return requests.HTTPError(
        f"Request failed with status {status_code}",
        response=Mock(status_code=status_code),
    )


def test_approval_workflow_loader_treats_argo_404_as_absent_workflow():
    argo_service = Mock()
    argo_service.get_workflow.side_effect = _http_error(404)

    assert load_workflow_for_approvals(
        argo_service,
        "migration-workflow",
        "ma",
    ) == {}


@pytest.mark.parametrize("status_code", [401, 500])
def test_approval_workflow_loader_preserves_argo_failures(status_code):
    argo_service = Mock()
    argo_service.get_workflow.side_effect = _http_error(status_code)

    with pytest.raises(requests.HTTPError) as raised:
        load_workflow_for_approvals(
            argo_service,
            "migration-workflow",
            "ma",
        )

    assert raised.value.response.status_code == status_code


def test_cloudwatch_log_group_url_builds_console_deep_link():
    assert cloudwatch_log_group_url(
        "us-east-2",
        "/migration-assistant-dev-us-east-2/logs",
    ) == (
        "https://console.aws.amazon.com/cloudwatch/home?region=us-east-2"
        "#logsV2:log-groups/log-group/"
        "$252Fmigration-assistant-dev-us-east-2$252Flogs"
    )


@pytest.mark.parametrize(
    ("region", "log_group"),
    [(None, "/logs"), ("us-east-2", None), ("", "/logs"), ("us-east-2", "")],
)
def test_cloudwatch_log_group_url_requires_explicit_configuration(
    region,
    log_group,
):
    assert cloudwatch_log_group_url(region, log_group) is None
