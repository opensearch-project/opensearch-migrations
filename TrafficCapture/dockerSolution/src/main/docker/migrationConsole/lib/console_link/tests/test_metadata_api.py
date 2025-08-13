import pytest
from unittest.mock import Mock, patch, MagicMock
from fastapi.testclient import TestClient
from console_link.api.main import app
from console_link.models.command_result import CommandResult

client = TestClient(app)


class TestMetadataAPI:
    """Test cases for the metadata migration API endpoints."""

    def setup_method(self):
        """Set up test fixtures."""
        self.session_id = "test-session"
        self.sample_request = {
            "index_allowlist": ["index1", "index2"],
            "index_template_allowlist": ["template1"],
            "component_template_allowlist": ["component1"]
        }

    @patch('console_link.api.metadata.get_environment')
    @patch('console_link.api.metadata.find_session')
    def test_evaluate_metadata_success(self, mock_find_session, mock_get_environment):
        """Test successful metadata evaluation."""
        # Mock session exists
        mock_find_session.return_value = {"name": self.session_id}
        
        # Mock environment and metadata
        mock_env = Mock()
        mock_metadata = Mock()
        mock_metadata.evaluate.return_value = CommandResult(success=True, value="Evaluation successful")
        mock_env.metadata = mock_metadata
        mock_get_environment.return_value = mock_env
        
        # Make request
        response = client.post(
            f"/sessions/{self.session_id}/metadata/evaluate",
            json=self.sample_request
        )
        
        # Verify response
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["session_id"] == self.session_id
        assert "result" in data
        
        # Verify metadata.evaluate was called with correct args
        expected_args = [
            "--index-allowlist", "index1,index2",
            "--index-template-allowlist", "template1",
            "--component-template-allowlist", "component1",
            "--output-format", "json"
        ]
        mock_metadata.evaluate.assert_called_once_with(extra_args=expected_args)

    @patch('console_link.api.metadata.get_environment')
    @patch('console_link.api.metadata.find_session')
    def test_migrate_metadata_success(self, mock_find_session, mock_get_environment):
        """Test successful metadata migration."""
        # Mock session exists
        mock_find_session.return_value = {"name": self.session_id}
        
        # Mock environment and metadata
        mock_env = Mock()
        mock_metadata = Mock()
        mock_metadata.migrate.return_value = CommandResult(success=True, value="Migration successful")
        mock_env.metadata = mock_metadata
        mock_get_environment.return_value = mock_env
        
        # Make request
        response = client.post(
            f"/sessions/{self.session_id}/metadata/migrate",
            json=self.sample_request
        )
        
        # Verify response
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["session_id"] == self.session_id
        assert "result" in data
        
        # Verify metadata.migrate was called with correct args
        expected_args = [
            "--index-allowlist", "index1,index2",
            "--index-template-allowlist", "template1",
            "--component-template-allowlist", "component1",
            "--output-format", "json"
        ]
        mock_metadata.migrate.assert_called_once_with(extra_args=expected_args)

    @patch('console_link.api.metadata.find_session')
    def test_session_not_found(self, mock_find_session):
        """Test error when session doesn't exist."""
        from fastapi import HTTPException
        mock_find_session.side_effect = HTTPException(status_code=404, detail="Session not found.")
        
        response = client.post(
            f"/sessions/nonexistent/metadata/evaluate",
            json={}
        )
        
        assert response.status_code == 404

    @patch('console_link.api.metadata.get_environment')
    @patch('console_link.api.metadata.find_session')
    def test_metadata_not_configured(self, mock_find_session, mock_get_environment):
        """Test error when metadata is not configured in environment."""
        # Mock session exists
        mock_find_session.return_value = {"name": self.session_id}
        
        # Mock environment without metadata
        mock_env = Mock()
        mock_env.metadata = None
        mock_get_environment.return_value = mock_env
        
        response = client.post(
            f"/sessions/{self.session_id}/metadata/evaluate",
            json={}
        )
        
        assert response.status_code == 500
        assert "not configured" in response.json()["detail"]

    @patch('console_link.api.metadata.get_environment')
    @patch('console_link.api.metadata.find_session')
    def test_metadata_operation_failure(self, mock_find_session, mock_get_environment):
        """Test metadata operation failure."""
        # Mock session exists
        mock_find_session.return_value = {"name": self.session_id}
        
        # Mock environment and metadata that fails
        mock_env = Mock()
        mock_metadata = Mock()
        mock_metadata.evaluate.return_value = CommandResult(success=False, value="Operation failed")
        mock_env.metadata = mock_metadata
        mock_get_environment.return_value = mock_env
        
        response = client.post(
            f"/sessions/{self.session_id}/metadata/evaluate",
            json={}
        )
        
        # Should return 200 but with success=False
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is False
        assert "error" in data

    def test_empty_request_body(self):
        """Test that empty request body is handled correctly."""
        with patch('console_link.api.metadata.find_session') as mock_find_session, \
             patch('console_link.api.metadata.get_environment') as mock_get_environment:
            
            # Mock session exists
            mock_find_session.return_value = {"name": self.session_id}
            
            # Mock environment and metadata
            mock_env = Mock()
            mock_metadata = Mock()
            mock_metadata.evaluate.return_value = CommandResult(success=True, value="Success")
            mock_env.metadata = mock_metadata
            mock_get_environment.return_value = mock_env
            
            # Make request with empty body
            response = client.post(
                f"/sessions/{self.session_id}/metadata/evaluate",
                json={}
            )
            
            assert response.status_code == 200
            # Verify metadata.evaluate was called with empty extra_args  
            expected_args = ["--output-format", "json"]
            mock_metadata.evaluate.assert_called_once_with(extra_args=expected_args)
