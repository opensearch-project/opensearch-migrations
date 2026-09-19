"""Version-bound reset planning and direct execution."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
from io import StringIO
from threading import RLock
from typing import Any, Callable, Dict, Mapping, Optional, Sequence, Tuple
from uuid import uuid4

from kubernetes import client

from ..commands.crd_utils import (
    CRD_GROUP,
    CRD_VERSION,
    resource_display_name,
)
from ..commands.reset import (
    _delete_targets,
    _reset_plan,
    _resolve_exact_reset_targets,
    _resolve_named_reset_targets,
)


class ResetUnavailable(RuntimeError):
    pass


class ResetPlanStale(RuntimeError):
    pass


class ResetExecutionFailed(RuntimeError):
    pass


@dataclass(frozen=True)
class ResetTarget:
    plural: str
    type: str
    name: str
    path: str
    phase: str
    depends_on: Tuple[str, ...]
    uid: str
    resource_version: str


@dataclass(frozen=True)
class ResetPlan:
    token: str
    request_target_id: str
    targets: Tuple[ResetTarget, ...]
    messages: Tuple[str, ...]
    warnings: Tuple[str, ...]


@dataclass(frozen=True)
class ResetExecutionResult:
    plan: ResetPlan
    message: str
    detail: Optional[str]


@dataclass(frozen=True)
class _StoredResetPlan:
    public: ResetPlan
    resolved_targets: Tuple[tuple[str, str, str, list[str]], ...]


class ResetService:
    def __init__(
        self,
        namespace: str,
        *,
        target_resolver: Optional[Callable[..., Any]] = None,
        exact_resolver: Optional[Callable[..., Any]] = None,
        plan_builder: Optional[Callable[..., Mapping[str, Any]]] = None,
        version_loader: Optional[
            Callable[[str, str], Mapping[str, Any]]
        ] = None,
        deleter: Optional[Callable[..., bool]] = None,
        custom_api: Optional[Any] = None,
        plan_limit: int = 30,
    ):
        self.namespace = namespace
        self._target_resolver = (
            target_resolver or _resolve_named_reset_targets
        )
        self._exact_resolver = exact_resolver or _resolve_exact_reset_targets
        self._plan_builder = plan_builder or _reset_plan
        self._version_loader = version_loader or self._load_version
        self._deleter = deleter or _delete_targets
        self._custom_api = custom_api
        self._plan_limit = max(1, plan_limit)
        self._lock = RLock()
        self._plans: Dict[str, _StoredResetPlan] = {}
        self._order: list[str] = []

    def plan(self, target_id: str) -> ResetPlan:
        return self.plan_many((target_id,))

    def plan_many(self, target_ids: Sequence[str]) -> ResetPlan:
        unique_target_ids = tuple(dict.fromkeys(target_ids))
        if not unique_target_ids:
            raise ResetUnavailable("At least one reset target is required.")
        requested = [
            _reset_target(target_id)
            for target_id in unique_target_ids
        ]
        messages: list[str] = []
        paths = [
            resource_display_name(plural, name)
            for plural, name in requested
        ]
        resolved = self._target_resolver(
            paths,
            self.namespace,
            True,
            True,
            messages,
        )
        if resolved is None:
            raise ResetUnavailable(
                "\n".join(messages)
                or f"Reset is blocked for {', '.join(paths)}."
            )
        plan_data = self._plan_builder(
            resolved,
            self.namespace,
            messages,
            False,
        )
        targets = tuple(
            self._target_from_plan(item)
            for item in plan_data.get("targets") or []
        )
        if not targets:
            raise ResetUnavailable(
                f"Reset plan is empty for {', '.join(paths)}."
            )
        token = uuid4().hex
        public = ResetPlan(
            token=token,
            request_target_id=unique_target_ids[0],
            targets=targets,
            messages=tuple(
                str(item) for item in plan_data.get("messages") or []
                if str(item)
            ),
            warnings=tuple(
                str(item) for item in plan_data.get("warnings") or []
                if str(item)
            ),
        )
        with self._lock:
            self._plans[token] = _StoredResetPlan(
                public=public,
                resolved_targets=tuple(resolved),
            )
            self._order.insert(0, token)
            for expired in self._order[self._plan_limit:]:
                self._plans.pop(expired, None)
            self._order = self._order[:self._plan_limit]
        return public

    def validate(self, token: str) -> ResetPlan:
        stored = self._stored(token)
        for target in stored.public.targets:
            try:
                version = self._version_loader(target.plural, target.name)
            except ResetPlanStale:
                raise
            except Exception as error:
                raise ResetPlanStale(
                    f"{target.path} is no longer available; create a new plan."
                ) from error
            if (
                str(version.get("uid") or "") != target.uid
                or str(version.get("resourceVersion") or "")
                != target.resource_version
            ):
                raise ResetPlanStale(
                    f"{target.path} changed after this reset plan was shown."
                )
        messages: list[str] = []
        exact = self._exact_resolver(
            [target.path for target in stored.public.targets],
            self.namespace,
            True,
            messages,
        )
        if exact is None:
            raise ResetPlanStale(
                "\n".join(messages)
                or "The reset dependency set changed; create a new plan."
            )
        expected = {
            (target.plural, target.name)
            for target in stored.public.targets
        }
        actual = {(item[0], item[1]) for item in exact}
        if actual != expected:
            raise ResetPlanStale(
                "The reset target set changed; create and review a new plan."
            )
        return stored.public

    def execute(self, token: str) -> ResetExecutionResult:
        plan = self.validate(token)
        with self._lock:
            stored = self._plans.pop(token, None)
            if stored is not None and token in self._order:
                self._order.remove(token)
        if stored is None:
            raise ResetPlanStale(
                "This reset plan is unknown or expired; create a new plan."
            )
        output = StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            succeeded = self._deleter(
                list(stored.resolved_targets),
                self.namespace,
                True,
            )
        detail = output.getvalue().strip() or None
        if not succeeded:
            raise ResetExecutionFailed(
                detail or "One or more reset targets could not be deleted."
            )
        return ResetExecutionResult(
            plan=plan,
            message=(
                f"Reset completed for {len(plan.targets)} "
                f"resource{'s' if len(plan.targets) != 1 else ''}"
            ),
            detail=detail,
        )

    def _stored(self, token: str) -> _StoredResetPlan:
        with self._lock:
            stored = self._plans.get(token)
        if stored is None:
            raise ResetPlanStale(
                "This reset plan is unknown or expired; create a new plan."
            )
        return stored

    def _target_from_plan(self, item: Mapping[str, Any]) -> ResetTarget:
        plural = str(item.get("plural") or "")
        name = str(item.get("name") or "")
        version = self._version_loader(plural, name)
        return ResetTarget(
            plural=plural,
            type=str(item.get("type") or plural),
            name=name,
            path=str(item.get("path") or resource_display_name(plural, name)),
            phase=str(item.get("phase") or "Unknown"),
            depends_on=tuple(
                str(value) for value in item.get("dependsOn") or []
            ),
            uid=str(version.get("uid") or ""),
            resource_version=str(version.get("resourceVersion") or ""),
        )

    def _load_version(self, plural: str, name: str) -> Mapping[str, Any]:
        custom_api = self._custom_api or client.CustomObjectsApi()
        resource = custom_api.get_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=self.namespace,
            plural=plural,
            name=name,
        )
        metadata = resource.get("metadata") or {}
        return {
            "uid": metadata.get("uid"),
            "resourceVersion": metadata.get("resourceVersion"),
        }


def _reset_target(target_id: str) -> tuple[str, str]:
    parts = str(target_id).split(":")
    if len(parts) != 3 or parts[0] != "reset":
        raise ResetUnavailable("The reset target identifier is invalid.")
    return parts[1], parts[2]
