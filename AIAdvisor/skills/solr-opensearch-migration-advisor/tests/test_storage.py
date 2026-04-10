"""Tests for storage.py — SessionState, InMemoryStorage, FileStorage."""
import sys
import os
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from storage import (
    SessionState,
    Incompatibility,
    ClientIntegration,
    StorageBackend,
    StorageInterface,
    InMemoryStorage,
    FileStorage,
)


# ---------------------------------------------------------------------------
# SessionState
# ---------------------------------------------------------------------------

def test_session_state_new():
    s = SessionState.new("s1")
    assert s.session_id == "s1"
    assert s.history == []
    assert s.facts == {}
    assert s.progress == 0
    assert s.incompatibilities == []


def test_session_state_append_turn():
    s = SessionState.new("s1")
    s.append_turn("hello", "hi")
    assert s.history == [{"user": "hello", "assistant": "hi"}]


def test_session_state_set_and_get_fact():
    s = SessionState.new("s1")
    s.set_fact("schema_migrated", True)
    assert s.get_fact("schema_migrated") is True
    assert s.get_fact("missing", "default") == "default"


def test_session_state_advance_progress():
    s = SessionState.new("s1")
    s.advance_progress(3)
    assert s.progress == 3
    s.advance_progress(2)  # should not go backwards
    assert s.progress == 3
    s.advance_progress(5)
    assert s.progress == 5


def test_session_state_add_incompatibility():
    s = SessionState.new("s1")
    s.add_incompatibility("schema", "Breaking", "copyField not supported", "Use copy_to")
    assert len(s.incompatibilities) == 1
    assert s.incompatibilities[0].severity == "Breaking"


def test_session_state_add_incompatibility_no_duplicates():
    s = SessionState.new("s1")
    s.add_incompatibility("schema", "Breaking", "desc", "rec")
    s.add_incompatibility("schema", "Breaking", "desc", "rec")
    assert len(s.incompatibilities) == 1


def test_session_state_roundtrip():
    s = SessionState.new("abc")
    s.append_turn("u", "a")
    s.set_fact("key", 42)
    s.advance_progress(2)
    s.add_incompatibility("query", "Behavioral", "TF-IDF vs BM25", "Configure similarity")
    s.add_client_integration("SolrJ", "library", "Java search client", "Replace with opensearch-java")
    d = s.to_dict()
    s2 = SessionState.from_dict(d)
    assert s2.session_id == "abc"
    assert s2.history == [{"user": "u", "assistant": "a"}]
    assert s2.facts == {"key": 42}
    assert s2.progress == 2
    assert len(s2.incompatibilities) == 1
    assert s2.incompatibilities[0].category == "query"
    assert len(s2.client_integrations) == 1
    assert s2.client_integrations[0].name == "SolrJ"


# ---------------------------------------------------------------------------
# ClientIntegration
# ---------------------------------------------------------------------------

def test_client_integration_roundtrip():
    c = ClientIntegration("pysolr", "library", "Python client", "Replace with opensearch-py")
    assert ClientIntegration.from_dict(c.to_dict()) == c


def test_session_state_add_client_integration():
    s = SessionState.new("s1")
    s.add_client_integration("SolrJ", "library", "Java client", "Replace with opensearch-java")
    assert len(s.client_integrations) == 1
    assert s.client_integrations[0].kind == "library"


def test_session_state_add_client_integration_no_duplicates():
    s = SessionState.new("s1")
    s.add_client_integration("SolrJ", "library", "notes", "action")
    s.add_client_integration("SolrJ", "library", "notes", "action")
    assert len(s.client_integrations) == 1


# ---------------------------------------------------------------------------
# Incompatibility
# ---------------------------------------------------------------------------

def test_incompatibility_roundtrip():
    i = Incompatibility("schema", "Breaking", "desc", "rec")
    assert Incompatibility.from_dict(i.to_dict()) == i


# ---------------------------------------------------------------------------
# InMemoryStorage
# ---------------------------------------------------------------------------

@pytest.fixture
def mem():
    return InMemoryStorage()


def test_inmemory_load_or_new_creates_blank(mem):
    s = mem.load_or_new("new-session")
    assert s.session_id == "new-session"
    assert s.history == []


def test_inmemory_save_and_load(mem):
    s = SessionState.new("s1")
    s.set_fact("x", 1)
    mem.save(s)
    loaded = mem.load("s1")
    assert loaded.get_fact("x") == 1


def test_inmemory_load_nonexistent_returns_none(mem):
    assert mem.load("ghost") is None


def test_inmemory_list_sessions(mem):
    mem.save(SessionState.new("a"))
    mem.save(SessionState.new("b"))
    assert set(mem.list_sessions()) == {"a", "b"}


def test_inmemory_delete(mem):
    mem.save(SessionState.new("s1"))
    mem.delete("s1")
    assert mem.load("s1") is None


def test_inmemory_delete_nonexistent_is_safe(mem):
    mem.delete("ghost")  # should not raise


def test_inmemory_is_storage_backend():
    assert issubclass(InMemoryStorage, StorageBackend)


# ---------------------------------------------------------------------------
# FileStorage
# ---------------------------------------------------------------------------

@pytest.fixture
def file_storage(tmp_path):
    return FileStorage(base_path=str(tmp_path))


def test_file_save_and_load(file_storage):
    s = SessionState.new("s1")
    s.set_fact("migrated", True)
    file_storage.save(s)
    loaded = file_storage.load("s1")
    assert loaded.get_fact("migrated") is True


def test_file_load_nonexistent_returns_none(file_storage):
    assert file_storage.load("ghost") is None


def test_file_load_or_new(file_storage):
    s = file_storage.load_or_new("brand-new")
    assert s.session_id == "brand-new"


def test_file_list_sessions(file_storage):
    file_storage.save(SessionState.new("x"))
    file_storage.save(SessionState.new("y"))
    assert set(file_storage.list_sessions()) == {"x", "y"}


def test_file_delete(file_storage):
    file_storage.save(SessionState.new("s1"))
    file_storage.delete("s1")
    assert file_storage.load("s1") is None


def test_file_delete_nonexistent_is_safe(file_storage):
    file_storage.delete("ghost")  # should not raise


def test_file_creates_directory(tmp_path):
    new_dir = str(tmp_path / "nested" / "sessions")
    FileStorage(base_path=new_dir)
    assert os.path.exists(new_dir)


def test_file_is_storage_backend():
    assert issubclass(FileStorage, StorageBackend)


def test_file_persists_incompatibilities(file_storage):
    s = SessionState.new("s1")
    s.add_incompatibility("schema", "Breaking", "desc", "rec")
    file_storage.save(s)
    loaded = file_storage.load("s1")
    assert len(loaded.incompatibilities) == 1
    assert loaded.incompatibilities[0].severity == "Breaking"


def test_file_persists_client_integrations(file_storage):
    s = SessionState.new("s1")
    s.add_client_integration("SolrJ", "library", "Java client", "Replace with opensearch-java")
    file_storage.save(s)
    loaded = file_storage.load("s1")
    assert len(loaded.client_integrations) == 1
    assert loaded.client_integrations[0].name == "SolrJ"


def test_file_overwrites_on_save(file_storage):
    s = SessionState.new("s1")
    s.set_fact("v", 1)
    file_storage.save(s)
    s.set_fact("v", 2)
    file_storage.save(s)
    assert file_storage.load("s1").get_fact("v") == 2


# ---------------------------------------------------------------------------
# Backwards-compatibility shim
# ---------------------------------------------------------------------------

def test_storage_interface_is_subclass_of_backend():
    assert issubclass(StorageInterface, StorageBackend)
