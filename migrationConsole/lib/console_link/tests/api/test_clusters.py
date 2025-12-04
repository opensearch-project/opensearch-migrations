import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock

from console_link.api.main import app
from console_link.models.cluster import Cluster, AuthMethod
from console_link.models.session import Session

client = TestClient(app)


@pytest.fixture
def mock_http_safe_find_session():
    """Fixture to mock the http_safe_find_session function from sessions module"""
    with patch("console_link.api.clusters.http_safe_find_session") as mock_find_session:
        yield mock_find_session


@pytest.fixture
def example_session_with_clusters():
    """Create an example session with both source and target clusters for testing"""
    session = MagicMock(spec=Session)
    session.env = MagicMock()

    # Mock source cluster with no_auth
    source_cluster = MagicMock(spec=Cluster)
    source_cluster.endpoint = "http://source-cluster:9200"
    source_cluster.auth_type = AuthMethod.NO_AUTH
    source_cluster.auth_details = None
    source_cluster.allow_insecure = True
    source_cluster.version = "2.7.0"

    # Mock target cluster with basic_auth
    target_cluster = MagicMock(spec=Cluster)
    target_cluster.endpoint = "https://target-cluster:9200"
    target_cluster.auth_type = AuthMethod.BASIC_AUTH
    target_cluster.auth_details = {
        "user_secret_arn": "arn:aws:secretsmanager:region:account:secret:name"
    }
    target_cluster.allow_insecure = False
    target_cluster.version = "2.9.0"
    target_cluster.get_basic_auth_details.return_value = MagicMock(username="admin", password="admin")

    session.env.source_cluster = source_cluster
    session.env.target_cluster = target_cluster

    return session


@pytest.fixture
def example_session_with_basic_auth_arn():
    """Create an example session with a source cluster using basic_auth_arn for testing"""
    session = MagicMock(spec=Session)
    session.env = MagicMock()

    # Mock source cluster with basic_auth_arn
    source_cluster = MagicMock(spec=Cluster)
    source_cluster.endpoint = "https://source-cluster:9200"
    source_cluster.auth_type = AuthMethod.BASIC_AUTH
    # Must match structure expected by convert_cluster_to_api_model function
    source_cluster.auth_details = {
        "user_secret_arn": "arn:aws:secretsmanager:region:account:secret:name"
    }
    source_cluster.allow_insecure = False
    source_cluster.version = "2.7.0"

    # No target cluster in this session
    session.env.source_cluster = source_cluster
    session.env.target_cluster = None

    return session


@pytest.fixture
def example_session_with_sigv4():
    """Create an example session with a source cluster using sigv4 auth for testing"""
    session = MagicMock(spec=Session)
    session.env = MagicMock()

    # Mock source cluster with sigv4
    source_cluster = MagicMock(spec=Cluster)
    source_cluster.endpoint = "https://source-cluster.amazonaws.com"
    source_cluster.auth_type = AuthMethod.SIGV4
    source_cluster.auth_details = {"region": "us-west-2", "service": "es"}
    source_cluster.allow_insecure = False
    source_cluster.version = None
    source_cluster._get_sigv4_details.return_value = ("es", "us-west-2")

    # No target cluster in this session
    session.env.source_cluster = source_cluster
    session.env.target_cluster = None

    return session


def test_get_source_cluster_no_auth(mock_http_safe_find_session, example_session_with_clusters):
    """Test getting source cluster with no_auth configuration"""
    mock_http_safe_find_session.return_value = example_session_with_clusters

    response = client.get("/sessions/test-session/clusters/source")

    assert response.status_code == 200
    data = response.json()
    assert data["endpoint"] == "http://source-cluster:9200"
    assert data["protocol"] == "http"
    assert data["enable_tls_verification"] is False
    assert data["version_override"] == "2.7.0"
    assert data["auth"]["type"] == "no_auth"


def test_get_target_cluster_basic_auth(mock_http_safe_find_session, example_session_with_clusters):
    """Test getting target cluster with basic_auth configuration"""
    mock_http_safe_find_session.return_value = example_session_with_clusters

    response = client.get("/sessions/test-session/clusters/target")

    assert response.status_code == 200
    data = response.json()
    assert data["endpoint"] == "https://target-cluster:9200"
    assert data["protocol"] == "https"
    assert data["enable_tls_verification"] is True
    assert data["version_override"] == "2.9.0"
    assert data["auth"]["type"] == "basic_auth_arn"
    assert data["auth"]["user_secret_arn"] == "arn:aws:secretsmanager:region:account:secret:name"


def test_get_source_cluster_basic_auth_arn(mock_http_safe_find_session, example_session_with_basic_auth_arn):
    """Test getting source cluster with basic_auth_arn configuration"""
    mock_http_safe_find_session.return_value = example_session_with_basic_auth_arn

    response = client.get("/sessions/test-session/clusters/source")

    assert response.status_code == 200
    data = response.json()
    assert data["endpoint"] == "https://source-cluster:9200"
    assert data["protocol"] == "https"
    assert data["enable_tls_verification"] is True
    assert data["auth"]["type"] == "basic_auth_arn"
    assert data["auth"]["user_secret_arn"] == "arn:aws:secretsmanager:region:account:secret:name"


def test_get_source_cluster_sigv4(mock_http_safe_find_session, example_session_with_sigv4):
    """Test getting source cluster with sigv4 auth configuration"""
    mock_http_safe_find_session.return_value = example_session_with_sigv4

    response = client.get("/sessions/test-session/clusters/source")

    assert response.status_code == 200
    data = response.json()
    assert data["endpoint"] == "https://source-cluster.amazonaws.com"
    assert data["protocol"] == "https"
    assert data["enable_tls_verification"] is True
    assert data["version_override"] is None
    assert data["auth"]["type"] == "sig_v4_auth"
    assert data["auth"]["service"] == "es"
    assert data["auth"]["region"] == "us-west-2"


@pytest.mark.parametrize(
    "path,status,detail",
    [
        ("/sessions/nonexistent-session/clusters/source", 404, "Session not found."),
        ("/sessions/nonexistent-session/clusters/target", 404, "Session not found."),
    ],
    ids=["source", "target"],
)
def test_get_cluster_session_not_found_paths(mock_http_safe_find_session, path, status, detail):
    mock_http_safe_find_session.side_effect = HTTPException(status_code=status, detail=detail)

    response = client.get(path)

    assert response.status_code == status
    assert response.json()["detail"] == detail


@pytest.mark.parametrize(
    "path,cluster_attr,detail",
    [
        ("/sessions/test-session/clusters/source", "source_cluster", "Source cluster not defined for this session"),
        ("/sessions/test-session/clusters/target", "target_cluster", "Target cluster not defined for this session"),
    ],
    ids=["source-not-defined", "target-not-defined"],
)
def test_get_cluster_not_defined(mock_http_safe_find_session, path, cluster_attr, detail):
    session = MagicMock(spec=Session)
    session.env = MagicMock()
    setattr(session.env, cluster_attr, None)
    mock_http_safe_find_session.return_value = session

    response = client.get(path)

    assert response.status_code == 404
    assert response.json()["detail"] == detail
