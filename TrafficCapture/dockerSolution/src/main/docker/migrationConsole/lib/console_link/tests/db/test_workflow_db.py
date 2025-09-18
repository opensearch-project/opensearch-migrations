import pytest
from datetime import datetime
from unittest.mock import patch
from console_link.db.workflow_db import (
    get_latest, create_entry, clear_all, WorkflowEntry, WorkflowNotAvailable
)


@pytest.fixture
def mock_db():
    with patch('console_link.db.workflow_db._DB') as mock_db:
        with patch('console_link.db.workflow_db._TABLE') as mock_table:
            with patch('console_link.db.workflow_db._QUERY') as mock_query:
                yield mock_db, mock_table, mock_query


@pytest.fixture
def valid_workflow_entry():
    return WorkflowEntry(
        session_name="test-session",
        workflow="test-workflow",
        timestamp=datetime.now()
    )


def test_get_latest_existing(mock_db):
    _, mock_table, mock_query = mock_db
    entry = {
        "session_name": "test-session",
        "workflow": "workflow1",
        "timestamp": datetime(2023, 1, 1).isoformat()
    }
    mock_table.get.return_value = [entry]
    
    result = get_latest("test-session")
    assert isinstance(result, WorkflowEntry)
    assert result.workflow == "workflow1"
    mock_table.get.assert_called_once()


def test_get_latest_nonexistent(mock_db):
    _, mock_table, mock_query = mock_db
    mock_table.get.return_value = None
    
    with pytest.raises(WorkflowNotAvailable):
        get_latest("nonexistent")
    mock_table.get.assert_called_once()


def test_get_latest_multiple_entries(mock_db):
    _, mock_table, mock_query = mock_db
    entry1 = {
        "session_name": "test-session",
        "workflow": "workflow1",
        "timestamp": datetime(2023, 1, 1).isoformat()
    }
    entry2 = {
        "session_name": "test-session",
        "workflow": "workflow2",
        "timestamp": datetime(2023, 1, 2).isoformat()  # More recent
    }
    mock_table.get.return_value = [entry1, entry2]
    
    result = get_latest("test-session")
    assert isinstance(result, WorkflowEntry)
    assert result.timestamp.day == 2  # Should get the more recent entry
    assert result.workflow == "workflow2"
    mock_table.get.assert_called_once()


def test_create_entry(mock_db, valid_workflow_entry):
    _, mock_table, _ = mock_db
    
    create_entry(valid_workflow_entry)
    mock_table.insert.assert_called_once_with(valid_workflow_entry.model_dump())


def test_clear_all(mock_db):
    _, mock_table, _ = mock_db
    
    clear_all()
    mock_table.truncate.assert_called_once()
