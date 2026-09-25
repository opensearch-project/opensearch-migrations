"""Tests for stateless, revisioned workflow configuration documents."""

from dataclasses import dataclass

import pytest

from console_link.workflow.application.config_documents import (
    CONFIGURATION_MODEL_VERSION,
    ConfigurationDocumentConflict,
    ConfigurationDocumentService,
)
from console_link.workflow.models.workflow_config_store import (
    StoredWorkflowConfigDocument,
    WorkflowConfigConflict,
)


@dataclass
class _Store:
    current: StoredWorkflowConfigDocument

    def __post_init__(self):
        self.saved = []

    def load_document(self, session_name):
        assert session_name == "default"
        return self.current

    def save_document(self, config, expected_revision, session_name):
        self.saved.append((config.raw_yaml, expected_revision, session_name))
        if expected_revision != self.current.persisted_revision:
            raise WorkflowConfigConflict(self.current)
        self.current = StoredWorkflowConfigDocument(
            raw_yaml=config.raw_yaml,
            persisted_revision="12",
        )
        return self.current


def test_load_exposes_raw_yaml_revision_and_model_version():
    service = ConfigurationDocumentService(
        store=_Store(StoredWorkflowConfigDocument("sourceClusters: {}\n", "11")),
        validate=lambda _raw_yaml: None,
    )

    document = service.load()

    assert document.raw_yaml == "sourceClusters: {}\n"
    assert document.persisted_revision == "11"
    assert document.model_version == CONFIGURATION_MODEL_VERSION


def test_save_validates_the_exact_payload_before_persisting_and_invalidates_drafts():
    validated = []
    invalidated = []
    store = _Store(StoredWorkflowConfigDocument("old: value\n", "11"))
    service = ConfigurationDocumentService(
        store=store,
        validate=validated.append,
        on_saved=lambda: invalidated.append(True),
    )

    document = service.save("11", "new: value\n")

    assert validated == ["new: value\n"]
    assert store.saved == [("new: value\n", "11", "default")]
    assert invalidated == [True]
    assert document.persisted_revision == "12"


def test_save_does_not_write_or_invalidate_when_validation_fails():
    store = _Store(StoredWorkflowConfigDocument("old: value\n", "11"))

    def reject(_raw_yaml):
        raise ValueError("Configuration validation failed")

    invalidated = []
    service = ConfigurationDocumentService(
        store=store,
        validate=reject,
        on_saved=lambda: invalidated.append(True),
    )

    with pytest.raises(ValueError, match="validation failed"):
        service.save("11", "broken: [\n")

    assert store.saved == []
    assert invalidated == []


def test_save_exposes_the_current_document_on_revision_conflict():
    store = _Store(StoredWorkflowConfigDocument("changed: elsewhere\n", "12"))
    validated = []
    service = ConfigurationDocumentService(
        store=store,
        validate=validated.append,
    )

    with pytest.raises(ConfigurationDocumentConflict) as error:
        service.save("11", "my: change\n")

    assert error.value.current.raw_yaml == "changed: elsewhere\n"
    assert error.value.current.persisted_revision == "12"
    assert validated == []
    assert store.saved == []
