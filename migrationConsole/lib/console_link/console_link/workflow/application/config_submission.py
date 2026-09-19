"""Stateless submission preparation bound to saved configuration revisions."""

from dataclasses import dataclass
from typing import Any, Dict, Optional

from .config_documents import (
    ConfigurationDocument,
    ConfigurationDocumentConflict,
)
from .config_review import (
    ConfigReviewChange,
    review_changes,
    validation_messages,
)


@dataclass(frozen=True)
class SavedConfigReview:
    persisted_revision: str
    valid: bool
    validation_messages: tuple[str, ...]
    changes: tuple[ConfigReviewChange, ...]


@dataclass(frozen=True)
class PreparedConfigSubmission:
    persisted_revision: str
    raw_yaml: str


class SavedConfigSubmissionService:
    """Loads, saves, reviews, and submits saved configuration documents."""

    def __init__(self, documents: Any, edit_service: Any):
        self._documents = documents
        self._edit_service = edit_service

    def load(self) -> ConfigurationDocument:
        return self._documents.load()

    def save(
        self,
        expected_persisted_revision: str,
        raw_yaml: str,
    ) -> ConfigurationDocument:
        return self._documents.save(expected_persisted_revision, raw_yaml)

    def review(
        self,
        expected_persisted_revision: str,
        snapshot: Optional[Any] = None,
    ) -> SavedConfigReview:
        document = self._require_document(expected_persisted_revision)
        edit_state = self._edit_service.project_raw_yaml(document.raw_yaml)
        validation = edit_state.get("validation") or {}
        return SavedConfigReview(
            persisted_revision=document.persisted_revision,
            valid=validation.get("valid") is not False,
            validation_messages=validation_messages(validation),
            changes=review_changes(edit_state, snapshot),
        )

    def preflight(
        self,
        expected_persisted_revision: str,
        workflow_name: str,
    ) -> Any:
        document = self._require_document(expected_persisted_revision)
        return self._edit_service.preflight_raw_config(
            document.raw_yaml,
            workflow_name,
        )

    def prepare(
        self,
        expected_persisted_revision: str,
    ) -> PreparedConfigSubmission:
        document = self._require_document(expected_persisted_revision)
        self._edit_service.validate_config_for_submit(document.raw_yaml)
        return PreparedConfigSubmission(
            persisted_revision=document.persisted_revision,
            raw_yaml=document.raw_yaml,
        )

    def submit(
        self,
        prepared: PreparedConfigSubmission,
        workflow_name: str,
    ) -> Dict[str, Any]:
        return self._edit_service.submit_raw_config(
            prepared.raw_yaml,
            workflow_name,
        )

    def _require_document(
        self,
        expected_persisted_revision: str,
    ) -> ConfigurationDocument:
        document = self._documents.load()
        if document.persisted_revision != expected_persisted_revision:
            raise ConfigurationDocumentConflict(document)
        return document
