"""Stateless, revisioned access to the saved workflow configuration."""

from dataclasses import dataclass
from typing import Callable, Optional

from ..models.config import WorkflowConfig
from ..models.workflow_config_store import (
    WorkflowConfigConflict,
    WorkflowConfigStore,
)


CONFIGURATION_MODEL_VERSION = "1"


@dataclass(frozen=True)
class ConfigurationDocument:
    raw_yaml: str
    persisted_revision: str
    model_version: str = CONFIGURATION_MODEL_VERSION


class ConfigurationDocumentConflict(RuntimeError):
    def __init__(self, current: ConfigurationDocument):
        super().__init__(
            "The saved workflow configuration changed; reload it before saving."
        )
        self.current = current


class ConfigurationDocumentService:
    """Loads and atomically saves configuration documents without a server draft."""

    def __init__(
        self,
        *,
        store: WorkflowConfigStore,
        validate: Callable[[str], None],
        session_name: str = "default",
        on_saved: Optional[Callable[[], None]] = None,
    ):
        self._store = store
        self._validate = validate
        self._session_name = session_name
        self._on_saved = on_saved

    def load(self) -> ConfigurationDocument:
        return _document(self._store.load_document(self._session_name))

    def save(
        self,
        expected_persisted_revision: str,
        raw_yaml: str,
    ) -> ConfigurationDocument:
        current = self.load()
        if current.persisted_revision != expected_persisted_revision:
            raise ConfigurationDocumentConflict(current)
        self._validate(raw_yaml)
        try:
            saved = self._store.save_document(
                WorkflowConfig(raw_yaml=raw_yaml),
                expected_persisted_revision,
                self._session_name,
            )
        except WorkflowConfigConflict as error:
            raise ConfigurationDocumentConflict(
                _document(error.current)
            ) from error
        if self._on_saved is not None:
            self._on_saved()
        return _document(saved)


def _document(stored) -> ConfigurationDocument:
    return ConfigurationDocument(
        raw_yaml=stored.raw_yaml,
        persisted_revision=stored.persisted_revision,
    )
