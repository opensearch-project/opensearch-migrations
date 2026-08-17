"""Best-effort Kubernetes admission preflight for pending migration resources."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import json
from typing import Any, Dict, Mapping, Optional, Sequence, Tuple

from kubernetes import client
from kubernetes.client.rest import ApiException

from ..commands.crd_utils import CRD_GROUP, CRD_VERSION
from ..resource_tree import RESOURCE_KIND_TO_PLURAL


RUN_NUMBER_LABEL = "migrations.opensearch.org/run-number"
WORKFLOW_LABEL = "migrations.opensearch.org/workflow-name"
BLOCKING_CLASSIFICATIONS = {"recreate-required", "invalid"}
_MISSING = object()


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


@dataclass(frozen=True)
class AdmissionPreflightReport:
    checked_resources: int
    issues: Tuple[AdmissionPreflightIssue, ...]

    @property
    def allowed(self) -> bool:
        return not any(issue.blocking for issue in self.issues)

    @property
    def blocking_issues(self) -> Tuple[AdmissionPreflightIssue, ...]:
        return tuple(issue for issue in self.issues if issue.blocking)

    @property
    def warning_issues(self) -> Tuple[AdmissionPreflightIssue, ...]:
        return tuple(issue for issue in self.issues if not issue.blocking)


class AdmissionPreflightService:
    """Ask the real API server whether pending root CR specs are admissible.

    The resolved-resource projection supplies the exact full spec used by the
    workflow. Kubernetes dry-run remains authoritative for deployed CRDs and
    policies. Projection policy metadata is used only when live admission is
    unavailable, so a known impossible field change can still be identified.
    """

    def __init__(self, namespace: str, *, custom_api: Optional[Any] = None):
        self.namespace = namespace
        self._custom_api = custom_api

    def check(
        self,
        resolved_config: Mapping[str, Any],
        *,
        workflow_name: str,
        run_number: str,
    ) -> AdmissionPreflightReport:
        issues: list[AdmissionPreflightIssue] = []
        resources = tuple(resolved_config.get("resources") or ())
        api = self._custom_api or client.CustomObjectsApi()

        for resource in resources:
            kind = str(resource.get("kind") or "")
            name = str(resource.get("name") or "")
            plural = RESOURCE_KIND_TO_PLURAL.get(kind)
            if not kind or not name or not plural:
                issues.append(AdmissionPreflightIssue(
                    kind=kind or "Unknown",
                    name=name or "unknown",
                    plural=plural,
                    classification="warning",
                    message=(
                        "Admission preflight skipped an unrecognized resolved "
                        "resource."
                    ),
                    source="preflight",
                ))
                continue

            try:
                existing = api.get_namespaced_custom_object(
                    group=CRD_GROUP,
                    version=CRD_VERSION,
                    namespace=self.namespace,
                    plural=plural,
                    name=name,
                )
            except ApiException as error:
                if error.status != 404:
                    issues.append(_issue_from_api_error(
                        kind, name, plural, error
                    ))
                    continue
                existing = None

            candidate = _candidate_resource(
                resource,
                existing=existing,
                namespace=self.namespace,
                workflow_name=workflow_name,
                run_number=run_number,
            )
            try:
                if existing is None:
                    api.create_namespaced_custom_object(
                        group=CRD_GROUP,
                        version=CRD_VERSION,
                        namespace=self.namespace,
                        plural=plural,
                        body=candidate,
                        dry_run="All",
                    )
                else:
                    api.replace_namespaced_custom_object(
                        group=CRD_GROUP,
                        version=CRD_VERSION,
                        namespace=self.namespace,
                        plural=plural,
                        name=name,
                        body=candidate,
                        dry_run="All",
                    )
            except ApiException as error:
                issue = _issue_from_api_error(
                    kind, name, plural, error
                )
                if (
                    existing is not None
                    and issue.classification == "warning"
                ):
                    policy_issues = _projection_policy_issues(
                        resource,
                        existing.get("spec") or {},
                        plural,
                    )
                    if policy_issues:
                        issues.extend(policy_issues)
                        continue
                issues.append(issue)

        return AdmissionPreflightReport(
            checked_resources=len(resources),
            issues=tuple(issues),
        )


def _candidate_resource(
    resource: Mapping[str, Any],
    *,
    existing: Optional[Mapping[str, Any]],
    namespace: str,
    workflow_name: str,
    run_number: str,
) -> Dict[str, Any]:
    metadata = deepcopy((existing or {}).get("metadata") or {})
    metadata.pop("managedFields", None)
    metadata["name"] = str(resource["name"])
    metadata["namespace"] = namespace
    labels = dict(metadata.get("labels") or {})
    labels.update({
        WORKFLOW_LABEL: workflow_name,
        RUN_NUMBER_LABEL: run_number,
    })
    metadata["labels"] = labels
    annotations = dict(metadata.get("annotations") or {})
    annotations.update(resource.get("annotations") or {})
    if annotations:
        metadata["annotations"] = annotations
    return {
        "apiVersion": str(
            resource.get("apiVersion")
            or f"{CRD_GROUP}/{CRD_VERSION}"
        ),
        "kind": str(resource["kind"]),
        "metadata": metadata,
        "spec": deepcopy(resource.get("parameters") or {}),
    }


def _issue_from_api_error(
    kind: str,
    name: str,
    plural: str,
    error: ApiException,
) -> AdmissionPreflightIssue:
    message = _api_error_message(error)
    lowered = message.lower()
    if (
        "impossible:" in lowered
        or (
            "permanently sealed" in lowered
            and "delete the resource" in lowered
        )
    ):
        classification = "recreate-required"
    elif (
        "gated changes detected" in lowered
        or (
            "approvalgate" in lowered
            and "approve" in lowered
        )
    ):
        classification = "approval-required"
    elif (
        error.status in {400, 422}
        and "validatingadmissionpolicy" not in lowered
        and _is_definite_schema_error(lowered)
    ):
        classification = "invalid"
    else:
        classification = "warning"
    return AdmissionPreflightIssue(
        kind=kind,
        name=name,
        plural=plural,
        classification=classification,
        message=message,
        source="kubernetes",
    )


def _is_definite_schema_error(message: str) -> bool:
    return (
        "strict decoding error" in message
        or "cannot unmarshal" in message
        or (
            "spec." in message
            and any(marker in message for marker in (
                "invalid value",
                "required value",
                "unsupported value",
                "unknown field",
                "must be",
            ))
        )
    )


def _api_error_message(error: ApiException) -> str:
    body = error.body
    if isinstance(body, bytes):
        body = body.decode("utf-8", errors="replace")
    if body:
        try:
            payload = json.loads(body)
            message = payload.get("message")
            if message:
                return str(message)
        except (TypeError, json.JSONDecodeError):
            return str(body)
    return str(error.reason or error)


def _projection_policy_issues(
    resource: Mapping[str, Any],
    previous_spec: Mapping[str, Any],
    plural: str,
) -> Tuple[AdmissionPreflightIssue, ...]:
    pending_spec = resource.get("parameters") or {}
    kind = str(resource.get("kind") or "")
    name = str(resource.get("name") or "")
    issues: list[AdmissionPreflightIssue] = []
    for policy in resource.get("parameterPolicies") or ():
        path = tuple(str(part) for part in policy.get("specPath") or ())
        if not path:
            continue
        previous = _value_at(previous_spec, path)
        pending = _value_at(pending_spec, path)
        if previous is _MISSING and pending is _MISSING:
            continue
        if previous is not _MISSING and pending is not _MISSING and previous == pending:
            continue
        path_label = ".".join(path)
        if policy.get("changeRestriction") == "impossible":
            issues.append(AdmissionPreflightIssue(
                kind=kind,
                name=name,
                plural=plural,
                classification="recreate-required",
                message=(
                    f"{path_label} cannot be changed. Delete and recreate."
                ),
                source="projection-policy",
            ))
        elif (
            policy.get("invariant") == "nonDecreasing"
            and isinstance(previous, (int, float))
            and isinstance(pending, (int, float))
            and pending < previous
        ):
            issues.append(AdmissionPreflightIssue(
                kind=kind,
                name=name,
                plural=plural,
                classification="recreate-required",
                message=f"{path_label} cannot decrease.",
                source="projection-policy",
            ))
        elif policy.get("changeRestriction") == "gated":
            issues.append(AdmissionPreflightIssue(
                kind=kind,
                name=name,
                plural=plural,
                classification="approval-required",
                message=(
                    f"{path_label} may require approval during the workflow."
                ),
                source="projection-policy",
            ))
    return tuple(issues)


def _value_at(source: Mapping[str, Any], path: Sequence[str]) -> Any:
    current: Any = source
    for part in path:
        if not isinstance(current, Mapping) or part not in current:
            return _MISSING
        current = current[part]
    return current
