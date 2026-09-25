import pytest

from console_link.workflow.application.config_documents import (
    ConfigurationDocument,
    ConfigurationDocumentConflict,
)
from console_link.workflow.application.config_submission import (
    SavedConfigSubmissionService,
)


class _Documents:
    def __init__(self):
        self.current = ConfigurationDocument(
            raw_yaml="sourceClusters: {}\n",
            persisted_revision="11",
        )
        self.saved = None

    def load(self):
        return self.current

    def save(self, expected_persisted_revision, raw_yaml):
        self.saved = (expected_persisted_revision, raw_yaml)
        self.current = ConfigurationDocument(
            raw_yaml=raw_yaml,
            persisted_revision="12",
        )
        return self.current


class _EditService:
    def __init__(self):
        self.preflight = None
        self.submitted = None
        self.validated = []

    def project_raw_yaml(self, raw_yaml):
        return {
            "nodes": [{
                "path": ["sourceClusters"],
                "label": "Source clusters",
                "status": "changed",
                "valueKind": "scalar",
                "children": [],
            }],
            "validation": {
                "valid": True,
                "errors": [],
                "diagnostics": [],
            },
        }

    def preflight_raw_config(self, raw_yaml, workflow_name):
        self.preflight = (raw_yaml, workflow_name)
        return {"allowed": True}

    def validate_config_for_submit(self, raw_yaml):
        self.validated.append(raw_yaml)

    def submit_raw_config(self, raw_yaml, workflow_name):
        self.submitted = (raw_yaml, workflow_name)
        return {"workflow_name": workflow_name}


def test_review_and_preflight_use_the_exact_saved_revision():
    documents = _Documents()
    edits = _EditService()
    service = SavedConfigSubmissionService(documents, edits)

    review = service.review("11")
    preflight = service.preflight("11", "migration")

    assert review.persisted_revision == "11"
    assert review.valid is True
    assert review.changes[0].path == "sourceClusters"
    assert preflight == {"allowed": True}
    assert edits.preflight == ("sourceClusters: {}\n", "migration")


def test_document_load_and_save_delegate_to_the_revisioned_store():
    documents = _Documents()
    service = SavedConfigSubmissionService(documents, _EditService())

    loaded = service.load()
    saved = service.save("11", "targetClusters: {}\n")

    assert loaded.persisted_revision == "11"
    assert saved.persisted_revision == "12"
    assert documents.saved == ("11", "targetClusters: {}\n")


def test_stale_saved_revision_is_rejected_before_validation():
    documents = _Documents()
    edits = _EditService()
    service = SavedConfigSubmissionService(documents, edits)

    with pytest.raises(ConfigurationDocumentConflict) as error:
        service.prepare("10")

    assert error.value.current.persisted_revision == "11"
    assert edits.validated == []


def test_worker_recheck_rejects_configuration_changed_after_acceptance():
    documents = _Documents()
    edits = _EditService()
    service = SavedConfigSubmissionService(documents, edits)

    accepted = service.prepare("11")
    documents.current = ConfigurationDocument(
        raw_yaml="targetClusters: {}\n",
        persisted_revision="12",
    )

    with pytest.raises(ConfigurationDocumentConflict):
        service.prepare(accepted.persisted_revision)

    assert edits.submitted is None


def test_captured_submission_does_not_drift_to_a_later_saved_document():
    documents = _Documents()
    edits = _EditService()
    service = SavedConfigSubmissionService(documents, edits)

    prepared = service.prepare("11")
    documents.current = ConfigurationDocument(
        raw_yaml="targetClusters: {}\n",
        persisted_revision="12",
    )
    result = service.submit(prepared, "migration")

    assert result == {"workflow_name": "migration"}
    assert edits.submitted == ("sourceClusters: {}\n", "migration")
