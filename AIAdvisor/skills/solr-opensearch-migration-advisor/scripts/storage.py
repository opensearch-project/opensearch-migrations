"""
Pluggable, session-resumable memory for the Solr-to-OpenSearch migration skill.

Session state is modelled as a typed :class:`SessionState` dataclass so that
all backends store and return the same structure.  New backends only need to
implement the four abstract methods on :class:`StorageBackend`.

Built-in backends
-----------------
* :class:`InMemoryStorage`  — ephemeral, process-scoped (good for tests and
  single-turn use).
* :class:`FileStorage`      — JSON files on disk, one file per session
  (default for persistent use).
"""

from __future__ import annotations

import json
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional


# ---------------------------------------------------------------------------
# Session state schema
# ---------------------------------------------------------------------------

@dataclass
class Incompatibility:
    """A single discovered incompatibility between Solr and OpenSearch."""
    category: str          # e.g. "schema", "query", "plugin"
    severity: str          # "Breaking" | "Behavioral" | "Unsupported"
    description: str
    recommendation: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "Incompatibility":
        return cls(
            category=d.get("category", ""),
            severity=d.get("severity", ""),
            description=d.get("description", ""),
            recommendation=d.get("recommendation", ""),
        )


@dataclass
class ClientIntegration:
    """Describes one client-side or front-end integration with Solr.

    Collected during Step 6 and surfaced in the migration report's
    Client & Front-end Impact section.
    """
    name: str           # e.g. "SolrJ", "pysolr", "React search UI"
    kind: str           # "library" | "ui" | "http" | "other"
    notes: str          # free-text description of current usage
    migration_action: str  # what needs to change for OpenSearch

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "ClientIntegration":
        return cls(
            name=d.get("name", ""),
            kind=d.get("kind", "other"),
            notes=d.get("notes", ""),
            migration_action=d.get("migration_action", ""),
        )


@dataclass
class SessionState:
    """Complete, resumable state for one migration session.

    Fields
    ------
    session_id:
        Unique identifier for this session.
    history:
        Ordered list of ``{"user": ..., "assistant": ...}`` turn dicts.
    facts:
        Arbitrary key/value store for discovered migration facts
        (e.g. ``schema_migrated``, ``customizations``).
    progress:
        Current workflow step number (0 = not started).
    incompatibilities:
        All incompatibilities discovered across every workflow step.
    client_integrations:
        Client-side and front-end integrations collected in Step 6.
    """
    session_id: str
    history: List[Dict[str, str]] = field(default_factory=list)
    facts: Dict[str, Any] = field(default_factory=dict)
    progress: int = 0
    incompatibilities: List[Incompatibility] = field(default_factory=list)
    client_integrations: List[ClientIntegration] = field(default_factory=list)

    # ------------------------------------------------------------------
    # Convenience helpers
    # ------------------------------------------------------------------

    def add_incompatibility(
        self,
        category: str,
        severity: str,
        description: str,
        recommendation: str,
    ) -> None:
        """Append an incompatibility, avoiding exact duplicates."""
        entry = Incompatibility(category, severity, description, recommendation)
        if entry not in self.incompatibilities:
            self.incompatibilities.append(entry)

    def add_client_integration(
        self,
        name: str,
        kind: str,
        notes: str,
        migration_action: str,
    ) -> None:
        """Record a client-side or front-end integration, avoiding exact duplicates."""
        entry = ClientIntegration(name, kind, notes, migration_action)
        if entry not in self.client_integrations:
            self.client_integrations.append(entry)

    def set_fact(self, key: str, value: Any) -> None:
        self.facts[key] = value

    def get_fact(self, key: str, default: Any = None) -> Any:
        return self.facts.get(key, default)

    def advance_progress(self, step: int) -> None:
        """Move progress forward (never backwards)."""
        if step > self.progress:
            self.progress = step

    def append_turn(self, user: str, assistant: str) -> None:
        self.history.append({"user": user, "assistant": assistant})

    # ------------------------------------------------------------------
    # Serialisation
    # ------------------------------------------------------------------

    def to_dict(self) -> Dict[str, Any]:
        return {
            "session_id": self.session_id,
            "history": self.history,
            "facts": self.facts,
            "progress": self.progress,
            "incompatibilities": [i.to_dict() for i in self.incompatibilities],
            "client_integrations": [c.to_dict() for c in self.client_integrations],
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "SessionState":
        return cls(
            session_id=d.get("session_id", ""),
            history=d.get("history", []),
            facts=d.get("facts", {}),
            progress=d.get("progress", 0),
            incompatibilities=[
                Incompatibility.from_dict(i)
                for i in d.get("incompatibilities", [])
            ],
            client_integrations=[
                ClientIntegration.from_dict(c)
                for c in d.get("client_integrations", [])
            ],
        )

    @classmethod
    def new(cls, session_id: str) -> "SessionState":
        """Create a blank session."""
        return cls(session_id=session_id)


# ---------------------------------------------------------------------------
# Storage interface
# ---------------------------------------------------------------------------

class StorageBackend(ABC):
    """Abstract base for pluggable session storage backends.

    Implementors only need to handle raw JSON-serialisable dicts; the
    :class:`SessionState` serialisation is handled by the base helpers.
    """

    # --- raw dict operations (implement these) ---

    @abstractmethod
    def _save_raw(self, session_id: str, data: Dict[str, Any]) -> None:
        """Persist a raw dict for *session_id*."""

    @abstractmethod
    def _load_raw(self, session_id: str) -> Optional[Dict[str, Any]]:
        """Return the raw dict for *session_id*, or ``None`` if absent."""

    @abstractmethod
    def delete(self, session_id: str) -> None:
        """Remove a session entirely."""

    @abstractmethod
    def list_sessions(self) -> List[str]:
        """Return all known session IDs."""

    # --- typed helpers (use these in application code) ---

    def save(self, state: SessionState) -> None:
        """Persist a :class:`SessionState`."""
        self._save_raw(state.session_id, state.to_dict())

    def load(self, session_id: str) -> Optional[SessionState]:
        """Load a :class:`SessionState`, or ``None`` if the session is new."""
        raw = self._load_raw(session_id)
        if raw is None:
            return None
        return SessionState.from_dict(raw)

    def load_or_new(self, session_id: str) -> SessionState:
        """Load an existing session or create a blank one."""
        return self.load(session_id) or SessionState.new(session_id)


# ---------------------------------------------------------------------------
# Backwards-compatibility shim
# ---------------------------------------------------------------------------

class StorageInterface(StorageBackend, ABC):
    """Deprecated alias kept for backwards compatibility.

    New code should subclass :class:`StorageBackend` directly.
    The old ``save(session_id, data)`` / ``load(session_id)`` signatures are
    preserved via overloads so existing callers continue to work.
    """

    # Provide the old raw-dict signatures as concrete pass-throughs so that
    # subclasses that only implement the old API still satisfy the ABC.
    def _save_raw(self, session_id: str, data: Dict[str, Any]) -> None:
        pass  # overridden by legacy subclasses via save()

    def _load_raw(self, session_id: str) -> Optional[Dict[str, Any]]:
        return None  # overridden by legacy subclasses via load()

    def delete(self, session_id: str) -> None:
        pass  # optional for legacy subclasses


# ---------------------------------------------------------------------------
# Built-in backends
# ---------------------------------------------------------------------------

class InMemoryStorage(StorageBackend):
    """Ephemeral in-process storage.

    All data is lost when the process exits.  Useful for tests and
    single-session CLI usage.
    """

    def __init__(self) -> None:
        self._store: Dict[str, Dict[str, Any]] = {}

    def _save_raw(self, session_id: str, data: Dict[str, Any]) -> None:
        self._store[session_id] = data

    def _load_raw(self, session_id: str) -> Optional[Dict[str, Any]]:
        return self._store.get(session_id)

    def delete(self, session_id: str) -> None:
        self._store.pop(session_id, None)

    def list_sessions(self) -> List[str]:
        return list(self._store.keys())


class FileStorage(StorageBackend):
    """JSON-file-per-session storage.

    Each session is stored as ``<base_path>/<session_id>.json``.
    The directory is created on first use if it does not exist.
    """

    def __init__(self, base_path: str = "sessions") -> None:
        self.base_path = base_path
        os.makedirs(base_path, exist_ok=True)

    def _get_path(self, session_id: str) -> str:
        return os.path.join(self.base_path, f"{session_id}.json")

    def _save_raw(self, session_id: str, data: Dict[str, Any]) -> None:
        with open(self._get_path(session_id), "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)

    def _load_raw(self, session_id: str) -> Optional[Dict[str, Any]]:
        path = self._get_path(session_id)
        if not os.path.exists(path):
            return None
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)

    def delete(self, session_id: str) -> None:
        path = self._get_path(session_id)
        if os.path.exists(path):
            os.remove(path)

    def list_sessions(self) -> List[str]:
        if not os.path.exists(self.base_path):
            return []
        return [
            f[:-5]
            for f in os.listdir(self.base_path)
            if f.endswith(".json")
        ]
