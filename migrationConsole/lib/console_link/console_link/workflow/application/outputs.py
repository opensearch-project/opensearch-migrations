"""Presentation-neutral managed workflow output access."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any, Callable, Dict, Mapping, Optional, Tuple

from kubernetes import client
from kubernetes.client.rest import ApiException

from ..commands.artifact_store import artifact_uri, read_artifact_text
from ..commands.crd_utils import CRD_GROUP, CRD_VERSION


DEFAULT_INLINE_LIMIT = 512 * 1024
OUTPUT_TARGET_PREFIX = "output"
OUTPUT_ID_PREFIX = "managed-output"
STAGES = {
    "metadataEvaluate": ("Evaluate", 0),
    "metadataMigrate": ("Migrate", 1),
}


class OutputError(RuntimeError):
    """Base class for output lookup and read failures."""


class OutputUnavailable(OutputError):
    """The requested resource or output reference is unavailable."""


class OutputStale(OutputError):
    """The output descriptor no longer identifies the current reference."""


class OutputReadFailed(OutputError):
    """The current output reference could not be read."""


@dataclass(frozen=True)
class OutputDescriptor:
    id: str
    target_id: str
    resource_id: str
    resource_plural: str
    resource_name: str
    output_name: str
    stage: str
    stage_order: int
    attempt: Optional[str]
    timestamp: Optional[str]
    source: str
    content_type: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "targetId": self.target_id,
            "resourceId": self.resource_id,
            "resourcePlural": self.resource_plural,
            "resourceName": self.resource_name,
            "outputName": self.output_name,
            "stage": self.stage,
            "stageOrder": self.stage_order,
            "attempt": self.attempt,
            "timestamp": self.timestamp,
            "source": self.source,
            "contentType": self.content_type,
        }


@dataclass(frozen=True)
class OutputInventory:
    target_id: str
    resource_id: str
    outputs: Tuple[OutputDescriptor, ...]


@dataclass(frozen=True)
class OutputContent:
    descriptor: OutputDescriptor
    content: Optional[str]
    inline: bool
    size: int
    message: Optional[str] = None


class OutputService:
    """Resolve CR-owned output references without exposing artifact keys as IDs."""

    def __init__(
        self,
        namespace: str,
        *,
        resource_loader: Optional[
            Callable[[str, str], Mapping[str, Any]]
        ] = None,
        artifact_reader: Optional[Callable[[str], str]] = None,
        artifact_source: Optional[Callable[[str], str]] = None,
        inline_limit: int = DEFAULT_INLINE_LIMIT,
        clock: Optional[Callable[[], Any]] = None,
    ):
        self.namespace = namespace
        self._resource_loader = resource_loader or self._load_resource
        self._artifact_reader = artifact_reader or read_artifact_text
        self._artifact_source = artifact_source or artifact_uri
        self.inline_limit = inline_limit
        # Kept injectable for deterministic service construction and future history.
        self._clock = clock

    def list_outputs(self, target_id: str) -> OutputInventory:
        plural, name, requested_output = _parse_target_id(target_id)
        resource = self._load(plural, name)
        refs = _output_refs(resource)
        if requested_output not in refs:
            raise OutputUnavailable(
                f"No managed output named '{requested_output}' is available "
                f"for {plural}/{name}."
            )
        descriptors = tuple(sorted(
            (
                self._descriptor(plural, name, output_name, ref)
                for output_name, ref in refs.items()
            ),
            key=lambda item: (
                item.stage_order,
                item.timestamp or "",
                item.output_name,
                item.id,
            ),
        ))
        return OutputInventory(
            target_id=target_id,
            resource_id=f"resource:{plural}:{name}",
            outputs=descriptors,
        )

    def read_output(self, output_id: str) -> OutputContent:
        descriptor, key = self._resolve_output_id(output_id)
        content = self._read(key)
        size = len(content.encode("utf-8"))
        if size > self.inline_limit:
            return OutputContent(
                descriptor=descriptor,
                content=None,
                inline=False,
                size=size,
                message=(
                    f"Output is {size} bytes, above the inline display limit. "
                    "Download it to inspect the complete content."
                ),
            )
        return OutputContent(
            descriptor=descriptor,
            content=content,
            inline=True,
            size=size,
        )

    def download_output(
        self,
        output_id: str,
    ) -> tuple[OutputDescriptor, bytes]:
        descriptor, key = self._resolve_output_id(output_id)
        return descriptor, self._read(key).encode("utf-8")

    def _load(self, plural: str, name: str) -> Mapping[str, Any]:
        try:
            return self._resource_loader(plural, name)
        except OutputError:
            raise
        except Exception as error:
            raise OutputUnavailable(
                f"Managed output resource {plural}/{name} is unavailable: "
                f"{error}"
            ) from error

    def _load_resource(self, plural: str, name: str) -> Mapping[str, Any]:
        try:
            return client.CustomObjectsApi().get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=self.namespace,
                plural=plural,
                name=name,
            )
        except ApiException as error:
            if error.status == 404:
                raise OutputUnavailable(
                    f"Managed output resource {plural}/{name} no longer exists."
                ) from error
            raise OutputUnavailable(
                f"Kubernetes could not read {plural}/{name}: {error}"
            ) from error

    def _descriptor(
        self,
        plural: str,
        name: str,
        output_name: str,
        ref: Mapping[str, Any],
    ) -> OutputDescriptor:
        key = str(ref["s3Key"])
        stage, stage_order = STAGES.get(
            output_name,
            (output_name, 100),
        )
        return OutputDescriptor(
            id=_output_id(plural, name, output_name, key),
            target_id=f"{OUTPUT_TARGET_PREFIX}:{plural}:{name}:{output_name}",
            resource_id=f"resource:{plural}:{name}",
            resource_plural=plural,
            resource_name=name,
            output_name=output_name,
            stage=stage,
            stage_order=stage_order,
            attempt=_optional_string(ref.get("workflowName")),
            timestamp=_optional_string(
                ref.get("workflowCreationTimestamp")
            ),
            source=self._artifact_source(key),
            content_type=_content_type(key),
        )

    def _resolve_output_id(
        self,
        output_id: str,
    ) -> tuple[OutputDescriptor, str]:
        plural, name, output_name, expected_digest = _parse_output_id(
            output_id
        )
        resource = self._load(plural, name)
        ref = _output_refs(resource).get(output_name)
        if not ref:
            raise OutputStale(
                f"The {output_name} output reference is no longer available."
            )
        key = str(ref["s3Key"])
        if _key_digest(key) != expected_digest:
            raise OutputStale(
                f"The {output_name} output reference changed. Refresh outputs "
                "before reading it."
            )
        return self._descriptor(plural, name, output_name, ref), key

    def _read(self, key: str) -> str:
        try:
            return self._artifact_reader(key)
        except OutputError:
            raise
        except Exception as error:
            raise OutputReadFailed(
                str(error) or type(error).__name__
            ) from error


def _output_refs(
    resource: Mapping[str, Any],
) -> Dict[str, Mapping[str, Any]]:
    outputs = ((resource.get("status") or {}).get("outputs") or {})
    return {
        str(name): ref
        for name, ref in outputs.items()
        if isinstance(ref, Mapping) and ref.get("s3Key")
    }


def _parse_target_id(target_id: str) -> tuple[str, str, str]:
    parts = str(target_id).split(":")
    if len(parts) != 4 or parts[0] != OUTPUT_TARGET_PREFIX:
        raise OutputUnavailable("The managed output target is invalid.")
    return parts[1], parts[2], parts[3]


def _output_id(
    plural: str,
    name: str,
    output_name: str,
    key: str,
) -> str:
    return ":".join((
        OUTPUT_ID_PREFIX,
        plural,
        name,
        output_name,
        _key_digest(key),
    ))


def _parse_output_id(output_id: str) -> tuple[str, str, str, str]:
    parts = str(output_id).split(":")
    if len(parts) != 5 or parts[0] != OUTPUT_ID_PREFIX:
        raise OutputUnavailable("The managed output identifier is invalid.")
    return parts[1], parts[2], parts[3], parts[4]


def _key_digest(key: str) -> str:
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:20]


def _content_type(key: str) -> str:
    lowered = key.lower()
    if lowered.endswith(".json"):
        return "application/json"
    if lowered.endswith((".yaml", ".yml")):
        return "application/yaml"
    return "text/plain"


def _optional_string(value: Any) -> Optional[str]:
    if value is None or value == "":
        return None
    return str(value)
