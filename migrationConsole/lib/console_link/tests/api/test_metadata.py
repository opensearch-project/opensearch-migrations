from unittest.mock import Mock, patch
from datetime import datetime, timezone
from fastapi.testclient import TestClient

from console_link.api.main import app
from console_link.models.session import Session
from console_link.models.step_state import StepState
from console_link.environment import Environment
from console_link.db.metadata_db import MetadataEntry, MetadataNotAvailable

client = TestClient(app)


class TestMetadataAPI:
    """Test cases for the metadata migration API endpoints."""

    def setup_method(self):
        """Set up test fixtures."""
        self.session_name = "test-session"
        self.sample_request = {
            "index_allowlist": ["index1", "index2"],
            "index_template_allowlist": ["template1"],
            "component_template_allowlist": ["component1"],
            "dry_run": True
        }
        self.mock_result = {
            "clusters": {
                "source": {"type": "snapshot", "version": "2.0.0"},
                "target": {"type": "live", "version": "2.0.0"}
            },
            "items": {
                "dryRun": True,
                "indexTemplates": [{"name": "template1", "successful": True}],
                "componentTemplates": [{"name": "component1", "successful": True}],
                "indexes": [{"name": "index1", "successful": True}],
                "aliases": []
            },
            "transformations": {
                "transformers": []
            }
        }

    def create_mock_session(self, has_metadata=True):
        """Helper to create a mock session with environment."""
        env = Environment(config={
            "source_cluster": {
                "endpoint": "http://source:9200",
                "no_auth": None
            },
            "target_cluster": {
                "endpoint": "http://target:9200",
                "no_auth": None
            }
        })

        if has_metadata:
            mock_metadata = Mock()
            mock_metadata.migrate_or_evaluate.return_value.output.stdout = str(self.mock_result)
            env.metadata = mock_metadata
        else:
            env.metadata = None

        return Session(
            name=self.session_name,
            created=datetime.now(timezone.utc),
            updated=datetime.now(timezone.utc),
            env=env
        )

    @patch('console_link.api.metadata.http_safe_find_session')
    @patch('console_link.api.metadata.metadata.parse_metadata_result')
    def test_migrate_metadata_success(self, mock_parse_result, mock_find_session):
        """Test successful metadata migration."""
        mock_find_session.return_value = self.create_mock_session()
        mock_parse_result.return_value = self.mock_result

        response = client.post(
            f"/sessions/{self.session_name}/metadata/migrate",
            json=self.sample_request
        )

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == StepState.COMPLETED
        assert "started" in data
        assert "finished" in data
        assert data["clusters"]["source"]["type"] == "snapshot"
        assert len(data["items"]["indexTemplates"]) == 1
        assert data["items"]["indexTemplates"][0]["name"] == "template1"

    @patch('console_link.api.metadata.http_safe_find_session')
    def test_migrate_metadata_not_configured(self, mock_find_session):
        """Test error when metadata is not configured in environment."""
        mock_find_session.return_value = self.create_mock_session(has_metadata=False)

        response = client.post(
            f"/sessions/{self.session_name}/metadata/migrate",
            json=self.sample_request
        )

        assert response.status_code == 400
        assert "not configured" in response.json()["detail"]

    @patch('console_link.api.metadata.http_safe_find_session')
    @patch('console_link.api.metadata.metadata.parse_metadata_result')
    def test_migrate_metadata_failure(self, mock_parse_result, mock_find_session):
        """Test metadata migration failure handling."""
        mock_find_session.return_value = self.create_mock_session()
        mock_parse_result.return_value = {
            "errorMessage": "Migration failed",
            "errorCount": 1,
            "errors": ["Error occurred"]
        }

        response = client.post(
            f"/sessions/{self.session_name}/metadata/migrate",
            json=self.sample_request
        )

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == StepState.FAILED
        assert data["errorMessage"] == "Migration failed"
        assert data["errorCount"] == 1
        assert data["errors"] == ["Error occurred"]

    @patch('console_link.api.metadata.http_safe_find_session')
    @patch('console_link.db.metadata_db.get_latest')
    def test_get_metadata_status_success(self, mock_get_latest, mock_find_session):
        """Test successful metadata status retrieval."""
        mock_find_session.return_value = self.create_mock_session()

        mock_entry = MetadataEntry(
            session_name=self.session_name,
            timestamp=datetime.now(timezone.utc),
            started=datetime.now(timezone.utc),
            finished=datetime.now(timezone.utc),
            dry_run=True,
            detailed_results=self.mock_result
        )
        mock_get_latest.return_value = mock_entry

        response = client.get(f"/sessions/{self.session_name}/metadata/status")

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == StepState.COMPLETED
        assert "started" in data
        assert "finished" in data
        assert data["items"]["dryRun"] is True

    @patch('console_link.api.metadata.http_safe_find_session')
    @patch('console_link.db.metadata_db.get_latest')
    def test_get_metadata_status_no_results(self, mock_get_latest, mock_find_session):
        """Test metadata status when no operations have been performed."""
        mock_find_session.return_value = self.create_mock_session()
        mock_get_latest.side_effect = MetadataNotAvailable()

        response = client.get(f"/sessions/{self.session_name}/metadata/status")

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == StepState.PENDING

    @patch('console_link.api.metadata.http_safe_find_session')
    @patch('console_link.db.metadata_db.get_latest')
    def test_get_metadata_status_error(self, mock_get_latest, mock_find_session):
        """Test metadata status retrieval error handling."""
        mock_find_session.return_value = self.create_mock_session()
        mock_get_latest.side_effect = Exception("Unexpected error")

        response = client.get(f"/sessions/{self.session_name}/metadata/status")

        assert response.status_code == 500
        assert "Failed to get metadata status" in response.json()["detail"]

    @patch('console_link.api.metadata.http_safe_find_session')
    def test_session_not_found(self, mock_find_session):
        """Test error when session doesn't exist."""
        from fastapi import HTTPException
        mock_find_session.side_effect = HTTPException(status_code=404, detail="Session not found")

        response = client.post(
            "/sessions/nonexistent/metadata/migrate",
            json=self.sample_request
        )

        assert response.status_code == 404
        assert "Session not found" in response.json()["detail"]

    @patch('console_link.api.metadata.http_safe_find_session')
    @patch('console_link.api.metadata.metadata.parse_metadata_result')
    def test_migrate_metadata_unexpected_error(self, mock_parse_result, mock_find_session):
        """Test handling of unexpected errors during migration."""
        mock_find_session.return_value = self.create_mock_session()
        mock_parse_result.side_effect = Exception("Unexpected error")

        response = client.post(
            f"/sessions/{self.session_name}/metadata/migrate",
            json=self.sample_request
        )

        assert response.status_code == 500
        assert "Unexpected error during metadata" in response.json()["detail"]
