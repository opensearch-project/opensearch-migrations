"""Python rendering types for config-processor submission preflight reports."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional, Tuple


BLOCKING_CLASSIFICATIONS = {"recreate-required", "invalid"}


@dataclass(frozen=True)
class AdmissionDeploymentAction:
    kind: str
    name: str
    plural: Optional[str]
    action: str
    reason: str
    message: str
    current_config_checksum: Optional[str] = None
    desired_config_checksum: Optional[str] = None

    @property
    def resource_id(self) -> Optional[str]:
        if not self.plural:
            return None
        return f"resource:{self.plural}:{self.name}"

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AdmissionDeploymentAction":
        return cls(
            kind=str(payload.get("kind") or "Unknown"),
            name=str(payload.get("name") or "unknown"),
            plural=(
                str(payload["plural"])
                if payload.get("plural")
                else None
            ),
            action=str(payload.get("action") or "reconcile"),
            reason=str(payload.get("reason") or "configuration-changed"),
            message=str(payload.get("message") or ""),
            current_config_checksum=(
                str(payload["currentConfigChecksum"])
                if payload.get("currentConfigChecksum")
                else None
            ),
            desired_config_checksum=(
                str(payload["desiredConfigChecksum"])
                if payload.get("desiredConfigChecksum")
                else None
            ),
        )


@dataclass(frozen=True)
class AdmissionPreflightIssue:
    kind: str
    name: str
    plural: Optional[str]
    classification: str
    message: str
    source: str

    @property
    def blocking(self) -> bool:
        return self.classification in BLOCKING_CLASSIFICATIONS

    @property
    def resource_id(self) -> Optional[str]:
        if not self.plural:
            return None
        return f"resource:{self.plural}:{self.name}"

    @property
    def reset_target_id(self) -> Optional[str]:
        if self.classification != "recreate-required" or not self.plural:
            return None
        return f"reset:{self.plural}:{self.name}"

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AdmissionPreflightIssue":
        return cls(
            kind=str(payload.get("kind") or "Unknown"),
            name=str(payload.get("name") or "unknown"),
            plural=(
                str(payload["plural"])
                if payload.get("plural")
                else None
            ),
            classification=str(
                payload.get("classification") or "warning"
            ),
            message=str(payload.get("message") or ""),
            source=str(payload.get("source") or "preflight"),
        )


@dataclass(frozen=True)
class AdmissionPreflightReport:
    checked_resources: int
    issues: Tuple[AdmissionPreflightIssue, ...]
    deployment_actions: Tuple[AdmissionDeploymentAction, ...] = ()

    @property
    def allowed(self) -> bool:
        return not any(issue.blocking for issue in self.issues)

    @property
    def blocking_issues(self) -> Tuple[AdmissionPreflightIssue, ...]:
        return tuple(issue for issue in self.issues if issue.blocking)

    @property
    def warning_issues(self) -> Tuple[AdmissionPreflightIssue, ...]:
        return tuple(issue for issue in self.issues if not issue.blocking)

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AdmissionPreflightReport":
        return cls(
            checked_resources=int(
                payload.get(
                    "checkedResources",
                    payload.get("checked_resources", 0),
                )
            ),
            issues=tuple(
                AdmissionPreflightIssue.from_payload(item)
                for item in payload.get("issues") or ()
            ),
            deployment_actions=tuple(
                AdmissionDeploymentAction.from_payload(item)
                for item in payload.get("deploymentActions") or ()
            ),
        )
