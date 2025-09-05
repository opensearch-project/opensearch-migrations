import pytest
from datetime import datetime, UTC
from unittest.mock import patch, MagicMock
from fastapi.testclient import TestClient

from console_link.api.main import app
from console_link.db.session_db import SessionAlreadyExists, SessionNotFound
from console_link.models.session import Session

client = TestClient(app)


@pytest.fixture
def mock_db():
    """Fixture to mock the database operations"""
    with patch("console_link.db.session_db.existence_check") as existence_check, \
         patch("console_link.db.session_db.find_session") as find_session, \
         patch("console_link.db.session_db.all_sessions") as all_sessions, \
         patch("console_link.db.session_db.create_session") as create_session, \
         patch("console_link.db.session_db.update_session") as update_session, \
         patch("console_link.db.session_db.delete_session") as delete_session:
        
        # Bundle them into a mock namespace for convenience
        db_mocks = MagicMock()
        db_mocks.existence_check = existence_check
        db_mocks.find_session = find_session
        db_mocks.all_sessions = all_sessions
        db_mocks.create_session = create_session
        db_mocks.update_session = update_session
        db_mocks.delete_session = delete_session

        yield db_mocks


@pytest.fixture
def mock_env():
    """Patch Environment in the module where it's used."""
    with patch("console_link.api.sessions.Environment", autospec=True) as env:
        inst = env.return_value
        # Populate whatever attributes your code reads off env
        inst.config = {
            "source_cluster": {
                "endpoint": "http://test-endpoint:9200",
                "no_auth": {}
            }
        }
        yield env


@pytest.fixture
def example_session():
    """Create an example session for testing"""
    now = datetime.now(UTC)
    return Session.model_validate({
        "name": "test-session",
        "created": now.isoformat(),
        "updated": now.isoformat(),
        "env": {
            "source_cluster": {
                "endpoint": "http://test-endpoint:9200",
                "no_auth": {}
            }
        }
    })


def test_list_sessions(mock_db, example_session):
    """Test listing all sessions"""
    mock_db.all_sessions.return_value = [example_session, example_session]
    
    response = client.get("/sessions/")
    assert response.status_code == 200
    assert len(response.json()) == 2
    assert response.json()[0]["name"] == "test-session"


def test_get_session(mock_db, example_session):
    """Test getting a single session"""
    mock_db.find_session.return_value = example_session
    mock_db.existence_check.return_value = example_session
    
    response = client.get("/sessions/test-session")
    assert response.status_code == 200
    assert response.json()["name"] == "test-session"
    mock_db.existence_check.find_session()
    mock_db.existence_check.assert_called_once()


def test_get_session_not_found(mock_db):
    """Test getting a session that doesn't exist"""
    mock_db.existence_check.side_effect = SessionNotFound
    
    response = client.get("/sessions/nonexistent")
    assert response.status_code == 404
    assert response.json()["detail"] == "Session not found."


def test_create_session(mock_db, mock_env):
    """Test creating a new session"""
    mock_db.create_session.return_value = example_session

    # Make the request
    response = client.post(
        "/sessions/",
        json={"name": "newSession"}
    )
    
    assert response.status_code == 201
    mock_env.assert_called_once_with(config={})


def test_create_session_already_exists(mock_db, mock_env):
    """Test creating a session that already exists"""
    # Session already exists
    mock_db.create_session.side_effect = SessionAlreadyExists
    
    response = client.post(
        "/sessions/",
        json={"name": "existing-session"}
    )
    
    assert response.status_code == 409
    assert response.json()["detail"] == "Session already exists."
    mock_env.assert_called_once_with(config={})


def test_update_session(mock_db, example_session):
    """Test updating an existing session with new fields"""
    # Session exists
    mock_db.existence_check.return_value = example_session
    mock_db.update_session.return_value = example_session

    response = client.put(
        "/sessions/test-session",
        json=example_session.model_dump()
    )
    
    assert response.status_code == 200
    mock_db.update_session.assert_called_once()
    assert response.json()["name"] == "test-session"
    assert response.json()["created"] == example_session.created.isoformat()
    assert response.json()["updated"] == example_session.updated.isoformat()


def test_delete_session(mock_db):
    """Test deleting a session"""
    # Session exists
    mock_db.delete_session.return_value = None
    
    response = client.delete("/sessions/test-session")
    
    assert response.status_code == 200
    assert response.json()["detail"] == "Session 'test-session' deleted."
    mock_db.delete_session.assert_called_once()


def test_delete_session_not_found(mock_db):
    """Test deleting a session that doesn't exist"""
    # Session doesn't exist
    mock_db.delete_session.side_effect = SessionNotFound()
    
    response = client.delete("/sessions/nonexistent")
    
    assert response.status_code == 404
    assert response.json()["detail"] == "Session not found."
