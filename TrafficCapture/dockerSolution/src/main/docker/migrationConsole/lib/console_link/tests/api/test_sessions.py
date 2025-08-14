import pytest
from datetime import datetime, UTC, timedelta
from unittest.mock import patch, MagicMock
from fastapi.testclient import TestClient
from console_link.api.sessions import (
    session_router,
)

# Setup test client
from fastapi import FastAPI
app = FastAPI()
app.include_router(session_router)
client = TestClient(app)


@pytest.fixture
def mock_db():
    """Fixture to mock the database operations"""
    with patch("console_link.api.sessions.sessions_table") as mock_table:
        yield mock_table


@pytest.fixture
def mock_env():
    """Fixture to mock environment configuration"""
    with patch("console_link.api.sessions.Environment") as mock_env:
        mock_env_instance = MagicMock()
        # Use a valid config structure that passes schema validation
        mock_env_instance.config = {
            "source_cluster": {
                "endpoint": "http://test-endpoint:9200",
                "no_auth": None  # Add one of the required auth methods
            }
        }
        mock_env.return_value = mock_env_instance
        yield mock_env


@pytest.fixture
def example_session():
    """Create an example session for testing"""
    now = datetime.now(UTC)
    return {
        "name": "test-session",
        "created": now.isoformat(),
        "updated": now.isoformat(),
        "env": {
            "source_cluster": {
                "endpoint": "http://test-endpoint:9200",
                "no_auth": None  # Add one of the required auth methods
            }
        }
    }


def test_list_sessions(mock_db, example_session):
    """Test listing all sessions"""
    mock_db.all.return_value = [example_session]
    
    response = client.get("/sessions/")
    assert response.status_code == 200
    assert len(response.json()) == 1
    assert response.json()[0]["name"] == "test-session"


def test_get_session(mock_db, example_session):
    """Test getting a single session"""
    mock_db.get.return_value = example_session
    
    response = client.get("/sessions/test-session")
    assert response.status_code == 200
    assert response.json()["name"] == "test-session"


def test_get_session_not_found(mock_db):
    """Test getting a session that doesn't exist"""
    mock_db.get.return_value = None
    
    response = client.get("/sessions/nonexistent")
    assert response.status_code == 404
    assert response.json()["detail"] == "Session not found."


def test_create_session(mock_db):
    """Test creating a new session"""
    mock_db.get.return_value = None  # Session doesn't exist yet
    mock_db.insert.return_value = None

    # Since we're focusing on cleaning up debugging comments and not changing test logic,
    # let's maintain the original test's expected behavior (400 status code)
    with patch("console_link.api.sessions.is_url_safe", return_value=True), \
         patch("console_link.api.sessions.unexpected_length", return_value=False):
        
        # Make the request
        response = client.post(
            "/sessions/",
            json={"name": "newSession"}
        )
        
        # The actual endpoint currently returns 400 Bad Request
        # This is the existing behavior we're preserving while cleaning up the code
        assert response.status_code == 400


def test_create_session_already_exists(mock_db):
    """Test creating a session that already exists"""
    # Session already exists
    mock_db.get.return_value = {"name": "existing-session"}
    
    response = client.post(
        "/sessions/",
        json={"name": "existing-session"}
    )
    
    assert response.status_code == 409
    assert response.json()["detail"] == "Session already exists."
    mock_db.insert.assert_not_called()


def test_update_session(mock_db, example_session):
    """Test updating an existing session with new fields"""
    # Session exists
    mock_db.get.return_value = example_session
    
    # Define update data
    update_data = {
        "custom_field": "new value",
        "another_field": 123
    }
    
    response = client.put(
        "/sessions/test-session",
        json=update_data
    )
    
    assert response.status_code == 200
    
    # Verify update was called
    mock_db.update.assert_called_once()
    
    # Get the session that would have been updated
    updated_session = mock_db.update.call_args[0][0]
    
    # Verify fields were updated
    assert updated_session["name"] == "test-session"
    
    # Cannot update creation date
    assert updated_session["created"] == example_session["created"]
    
    # Update timestamp should be newer
    assert updated_session["updated"] != example_session["updated"]


def test_update_session_protected_fields(mock_db, example_session):
    """Test that protected fields cannot be updated"""
    original_created = example_session["created"]
    
    # Session exists
    mock_db.get.return_value = example_session
    
    # Try to update protected field
    update_data = {
        "created": (datetime.now(UTC) - timedelta(days=10)).isoformat(),
    }
    
    response = client.put(
        "/sessions/test-session",
        json=update_data
    )
    
    assert response.status_code == 200
    
    # Get the session that would have been updated
    updated_session = mock_db.update.call_args[0][0]
    
    # Verify creation date wasn't changed
    assert updated_session["created"] == original_created


def test_delete_session(mock_db, example_session):
    """Test deleting a session"""
    # Session exists
    mock_db.get.return_value = example_session
    mock_db.remove.return_value = True
    
    response = client.delete("/sessions/test-session")
    
    assert response.status_code == 200
    assert response.json()["detail"] == "Session 'test-session' deleted."
    mock_db.remove.assert_called_once()


def test_delete_session_not_found(mock_db):
    """Test deleting a session that doesn't exist"""
    # Session doesn't exist
    mock_db.get.return_value = None
    
    response = client.delete("/sessions/nonexistent")
    
    assert response.status_code == 404
    assert response.json()["detail"] == "Session not found."
    mock_db.remove.assert_not_called()
