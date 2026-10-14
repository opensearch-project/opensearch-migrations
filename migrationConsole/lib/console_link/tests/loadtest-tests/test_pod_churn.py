import base64
import json
from types import SimpleNamespace

import pytest

from console_link.loadtest import pod_churn


@pytest.mark.parametrize(("isr", "healthy"), (
    ("0,1,2", True),
    ("0,1", False),
))
def test_topic_recovery_requires_every_replica_in_isr(isr, healthy):
    description = "\n".join(
        f"Topic: capture-proxy Partition: {partition} Leader: 0 "
        f"Replicas: 0,1,2 Isr: {isr}"
        for partition in range(8)
    )

    assert pod_churn._topic_fully_replicated(description, 8, 3) is healthy


def test_topic_replication_factor_can_be_lower_than_broker_count():
    pod_churn._validate_topic_replica_count(replicas=3, broker_count=12)


@pytest.mark.parametrize("replicas", (0, 13))
def test_topic_replication_factor_must_fit_the_broker_pool(replicas):
    with pytest.raises(ValueError):
        pod_churn._validate_topic_replica_count(replicas, broker_count=12)


def test_run_specs_preserve_each_child_runner_count():
    assert pod_churn._run_specs(
        '[{"name":"pinned","parallelism":"4"},'
        '{"name":"spread","parallelism":"2"}]'
    ) == {"pinned": 4, "spread": 2}


@pytest.mark.parametrize(("policy", "fail_closed"), (
    ("fail-closed", True),
    ("fail-open", False),
    (None, True),
))
@pytest.mark.parametrize("base64_encoded", (True, False))
def test_proxy_failure_policy_is_read_from_inline_json(policy, fail_closed, base64_encoded):
    config = {} if policy is None else {"captureFailurePolicy": policy}
    raw_config = json.dumps(config)
    inline_config = (
        base64.b64encode(raw_config.encode()).decode()
        if base64_encoded else raw_config
    )
    deployment = SimpleNamespace(
        spec=SimpleNamespace(
            template=SimpleNamespace(
                spec=SimpleNamespace(
                    containers=[SimpleNamespace(args=["---INLINE-JSON", inline_config])],
                ),
            ),
        ),
    )

    assert pod_churn._proxy_is_fail_closed(deployment) is fail_closed
