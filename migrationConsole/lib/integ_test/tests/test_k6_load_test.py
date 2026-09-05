from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from kubernetes.client.rest import ApiException

from console_link.models.cluster import Cluster
from integ_test.test_cases import k6_load_test_tests as k6_test


class _Session:
    region_name = "us-west-2"

    def __init__(self, credentials):
        self._credentials = credentials

    def get_credentials(self):
        return self._credentials


def _credentials(token="session-token"):
    frozen = SimpleNamespace(access_key="A" * 20, secret_key="secret", token=token)
    return SimpleNamespace(get_frozen_credentials=lambda: frozen)


def test_k6_auth_secret_data_supports_basic_auth():
    cluster = Cluster({
        "endpoint": "https://source:9200",
        "basic_auth": {"username": "admin", "password": "password"},
    })

    assert k6_test._k6_auth_secret_data(cluster) == {
        "K6_AUTH_MODE": "basic",
        "K6_AUTH_USERNAME": "admin",
        "K6_AUTH_PASSWORD": "password",
    }


def test_k6_auth_secret_data_supports_sigv4_through_proxy():
    cluster = Cluster({
        "endpoint": "https://search-source.us-east-1.es.amazonaws.com",
        "sigv4": {"region": "us-east-1", "service": "es"},
    })

    data = k6_test._k6_auth_secret_data(cluster, session=_Session(_credentials()))

    assert data == {
        "K6_AUTH_MODE": "sigv4",
        "AWS_ACCESS_KEY_ID": "A" * 20,
        "AWS_SECRET_ACCESS_KEY": "secret",
        "AWS_SESSION_TOKEN": "session-token",
        "AWS_REGION": "us-east-1",
        "SIGV4_SERVICE": "es",
        "SIGV4_SIGNING_ENDPOINT": cluster.endpoint,
    }


def test_k6_auth_secret_data_uses_session_region_and_omits_empty_token():
    cluster = Cluster({"endpoint": "https://source", "sigv4": None})

    data = k6_test._k6_auth_secret_data(
        cluster, session=_Session(_credentials(token=None)))

    assert data["AWS_REGION"] == "us-west-2"
    assert data["SIGV4_SERVICE"] == "es"
    assert "AWS_SESSION_TOKEN" not in data


def test_k6_auth_secret_data_requires_aws_credentials():
    cluster = Cluster({
        "endpoint": "https://source",
        "sigv4": {"region": "us-east-1"},
    })

    with pytest.raises(RuntimeError, match="requires AWS credentials"):
        k6_test._k6_auth_secret_data(cluster, session=_Session(None))


def test_k6_auth_secret_data_allows_no_auth():
    cluster = Cluster({"endpoint": "http://source:9200", "no_auth": None})

    assert k6_test._k6_auth_secret_data(cluster) == {}


def test_create_k6_auth_secret_marks_it_for_test_cleanup(monkeypatch):
    core = Mock()
    monkeypatch.setattr(k6_test.client, "CoreV1Api", lambda: core)

    k6_test._create_k6_auth_secret("ma", "k6-auth-123", {"K6_AUTH_MODE": "basic"})

    secret = core.create_namespaced_secret.call_args.kwargs["body"]
    assert core.create_namespaced_secret.call_args.kwargs["namespace"] == "ma"
    assert secret.metadata.name == "k6-auth-123"
    assert secret.metadata.labels == {"migration-test": "true"}
    assert secret.string_data == {"K6_AUTH_MODE": "basic"}


def test_delete_k6_auth_secret_ignores_not_found(monkeypatch):
    core = Mock()
    core.delete_namespaced_secret.side_effect = ApiException(status=404)
    monkeypatch.setattr(k6_test.client, "CoreV1Api", lambda: core)

    k6_test._delete_k6_auth_secret("ma", "gone")
