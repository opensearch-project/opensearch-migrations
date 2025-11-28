import pytest
from datetime import datetime
from unittest.mock import patch
from console_link.db.metadata_db import (
    get_latest, create_entry, MetadataEntry, MetadataNotAvailable
)


@pytest.fixture
def mock_db():
    with patch('console_link.db.metadata_db._DB') as mock_db:
        with patch('console_link.db.metadata_db._TABLE') as mock_table:
            with patch('console_link.db.metadata_db._QUERY') as mock_query:
                yield mock_db, mock_table, mock_query


@pytest.fixture
def valid_metadata_entry():
    return MetadataEntry(
        session_name="test-session",
        timestamp=datetime.now(),
        started=datetime.now(),
        finished=datetime.now(),
        dry_run=False,
        detailed_results={"status": "success"}
    )


def test_get_latest_existing(mock_db):
    _, mock_table, mock_query = mock_db
    entry1 = {
        "session_name": "test-session",
        "timestamp": datetime(2023, 1, 1).isoformat(),
        "started": datetime(2023, 1, 1).isoformat(),
        "finished": datetime(2023, 1, 1, 1).isoformat(),
        "dry_run": False,
        "detailed_results": {}
    }
    entry2 = {
        "session_name": "test-session",
        "timestamp": datetime(2023, 1, 2).isoformat(),  # More recent
        "started": datetime(2023, 1, 2).isoformat(),
        "finished": datetime(2023, 1, 2, 1).isoformat(),
        "dry_run": True,
        "detailed_results": {"status": "success"}
    }
    mock_table.search.return_value = [entry1, entry2]
    
    result = get_latest("test-session")
    assert isinstance(result, MetadataEntry)
    assert result.timestamp.day == 2  # Should get the more recent entry
    assert result.dry_run is True
    mock_table.search.assert_called_once()


def test_get_latest_nonexistent(mock_db):
    _, mock_table, mock_query = mock_db
    mock_table.search.return_value = []
    
    with pytest.raises(MetadataNotAvailable):
        get_latest("nonexistent")
    mock_table.search.assert_called_once()


def test_create_entry(mock_db, valid_metadata_entry):
    _, mock_table, _ = mock_db
    
    create_entry(valid_metadata_entry)
    mock_table.insert.assert_called_once_with(valid_metadata_entry.model_dump())
