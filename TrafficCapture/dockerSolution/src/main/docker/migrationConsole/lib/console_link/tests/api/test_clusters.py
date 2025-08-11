import pytest
from unittest.mock import Mock, patch

from console_link.api.clusters import convert_cluster_to_api_model, NoAuth, BasicAuth, SigV4Auth, ClusterInfo
from console_link.models.cluster import Cluster, AuthMethod


class TestConvertClusterToApiModel:
    def test_convert_none_cluster_raises_exception(self):
        """Test that None cluster raises an exception."""
        # Note: This is deliberately passing None to test exception handling
        with pytest.raises(Exception):
            # mypy/pylance will complain about this, but it's intentional for the test
            convert_cluster_to_api_model(None)  # type: ignore

    def test_convert_cluster_with_no_auth(self):
        """Test converting a cluster with no authentication."""
        # Create a mock cluster with no auth
        mock_cluster = Mock(spec=Cluster)
        mock_cluster.endpoint = "http://example.com:9200"
        mock_cluster.auth_type = AuthMethod.NO_AUTH
        mock_cluster.auth_details = None
        mock_cluster.allow_insecure = True
        mock_cluster.version = None

        # Convert the cluster
        result = convert_cluster_to_api_model(mock_cluster)

        # Verify the result
        assert isinstance(result, ClusterInfo)
        assert result.endpoint == "http://example.com:9200"
        assert result.protocol == "http"
        assert result.enable_tls_verification is False
        assert isinstance(result.auth, NoAuth)
        assert result.auth.type == "no_auth"
        assert result.version_override is None

    def test_convert_cluster_with_https_protocol(self):
        """Test that https protocol is properly extracted."""
        # Create a mock cluster with HTTPS endpoint
        mock_cluster = Mock(spec=Cluster)
        mock_cluster.endpoint = "https://example.com:9200"
        mock_cluster.auth_type = AuthMethod.NO_AUTH
        mock_cluster.auth_details = None
        mock_cluster.allow_insecure = False
        mock_cluster.version = None

        # Convert the cluster
        result = convert_cluster_to_api_model(mock_cluster)

        # Verify the result
        assert result.protocol == "https"
        assert result.enable_tls_verification is True

    def test_convert_cluster_with_version_override(self):
        """Test that version_override is properly mapped."""
        # Create a mock cluster with version override
        mock_cluster = Mock(spec=Cluster)
        mock_cluster.endpoint = "http://example.com:9200"
        mock_cluster.auth_type = AuthMethod.NO_AUTH
        mock_cluster.auth_details = None
        mock_cluster.allow_insecure = True
        mock_cluster.version = "7.10.2"

        # Convert the cluster
        result = convert_cluster_to_api_model(mock_cluster)

        # Verify the result
        assert result.version_override == "7.10.2"

    def test_convert_cluster_with_basic_auth(self):
        """Test converting a cluster with basic authentication."""
        # Create a mock cluster with basic auth
        mock_cluster = Mock(spec=Cluster)
        mock_cluster.endpoint = "https://example.com:9200"
        mock_cluster.auth_type = AuthMethod.BASIC_AUTH
        mock_cluster.auth_details = {"username": "admin", "password": "secret"}
        mock_cluster.allow_insecure = False
        mock_cluster.version = None

        # Mock get_basic_auth_details to return a named tuple with username and password
        mock_auth_details = Mock()
        mock_auth_details.username = "admin"
        mock_auth_details.password = "secret"
        mock_cluster.get_basic_auth_details.return_value = mock_auth_details

        # Convert the cluster
        result = convert_cluster_to_api_model(mock_cluster)

        # Verify the result
        assert isinstance(result, ClusterInfo)
        assert isinstance(result.auth, BasicAuth)
        assert result.auth.type == "basic_auth"
        assert result.auth.username == "admin"
        # Ensure password is not included in the response
        assert not hasattr(result.auth, "password")

    def test_convert_cluster_with_sigv4_auth(self):
        """Test converting a cluster with SigV4 authentication."""
        # Create a mock cluster with SigV4 auth
        mock_cluster = Mock(spec=Cluster)
        mock_cluster.endpoint = "https://example.com:9200"
        mock_cluster.auth_type = AuthMethod.SIGV4
        mock_cluster.auth_details = {"region": "us-west-2", "service": "es"}
        mock_cluster.allow_insecure = False
        mock_cluster.version = None

        # Mock _get_sigv4_details to return the expected values
        mock_cluster._get_sigv4_details.return_value = ("es", "us-west-2")

        # Convert the cluster
        result = convert_cluster_to_api_model(mock_cluster)

        # Verify the result
        assert isinstance(result, ClusterInfo)
        assert isinstance(result.auth, SigV4Auth)
        assert result.auth.type == "sigv4_auth"
        assert result.auth.region == "us-west-2"
        assert result.auth.service == "es"
