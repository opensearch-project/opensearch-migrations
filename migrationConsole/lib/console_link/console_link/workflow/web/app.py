"""FastAPI application factory for native workflow manage."""

from contextlib import asynccontextmanager
import asyncio
from dataclasses import dataclass, replace
import json
import logging
import os
from pathlib import Path
from typing import Annotated, Any, AsyncIterator, Dict, Optional

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles

from ..application.observations import ObservationCoordinator, ObservationEvent
from ..application.config_documents import ConfigurationDocumentConflict
from ..application.external_resources import (
    ExternalResourceSelectionWarning,
)
from ..application.outputs import (
    OutputReadFailed,
    OutputStale,
    OutputUnavailable,
)
from ..application.logs import (
    LogTargetInventory,
    LogTargetStale,
    LogUnavailable,
)
from ..application.operations import (
    OperationEvent,
    OperationWorkResult,
)
from ..application.actions import ApprovalStale, ApprovalUnavailable
from ..application.resets import (
    ResetPlanStale,
    ResetUnavailable,
)
from ..application.runtime_status import RuntimeStatusUnavailable
from ..commands.autocomplete_workflows import DEFAULT_WORKFLOW_NAME
from .contracts import (
    AdmissionPreflightV1,
    ApproveRequestV1,
    ApprovalGateInventoryV1,
    ApprovalReviewV1,
    ConfigEnvironmentDiagnosticsRequestV1,
    ConfigEnvironmentDiagnosticsV1,
    ConnectivityInventoryRequestV1,
    ConnectivityInventoryV1,
    ConnectivityTargetV1,
    ConfigurationDocumentV1,
    ConfigurationSchemaV1,
    ConfigReviewV1,
    ExecuteResetRequestV1,
    ExternalResourceContextRequestV1,
    ExternalResourceDetailsRequestV1,
    ExternalResourceDetailsV1,
    ExternalResourceInventoryV1,
    ExternalResourceMutationV1,
    ExternalResourceSelectionV1,
    HealthV1,
    LogEventV1,
    LogPageV1,
    LogStreamStatusV1,
    LogStreamV1,
    LogTargetInventoryV1,
    ManageSnapshotV1,
    OutputContentV1,
    OutputInventoryV1,
    OperationListV1,
    OperationV1,
    PersistedRevisionRequestV1,
    ResetPlanRequestV1,
    ResetPlanV1,
    RuntimeStatusV1,
    SaveExternalResourceRequestV1,
    SaveConfigurationDocumentRequestV1,
    SelectExternalResourceRequestV1,
    SetPreapprovalRequestV1,
    SetPreapprovalResponseV1,
    StartLogStreamRequestV1,
    StartConnectivityChecksRequestV1,
)


DEFAULT_STATIC_DIR = Path(__file__).with_name("static")
OBSERVATION_NOT_CONFIGURED = "Workflow observation is not configured"
SSE_MEDIA_TYPE = "text/event-stream"
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class WebAppSettings:
    workflow_name: str = DEFAULT_WORKFLOW_NAME
    external_logs_url: Optional[str] = None


def create_app(
    static_dir: Optional[Path] = None,
    coordinator: Optional[ObservationCoordinator] = None,
    external_resources: Optional[Any] = None,
    config_documents: Optional[Any] = None,
    config_diagnostics: Optional[Any] = None,
    connectivity: Optional[Any] = None,
    outputs: Optional[Any] = None,
    operations: Optional[Any] = None,
    approvals: Optional[Any] = None,
    resets: Optional[Any] = None,
    logs: Optional[Any] = None,
    runtime_status: Optional[Any] = None,
    settings: Optional[WebAppSettings] = None,
    config_schema_path: Optional[Path] = None,
) -> FastAPI:
    resolved_settings = settings or WebAppSettings()
    workflow_name = resolved_settings.workflow_name
    external_logs_url = resolved_settings.external_logs_url
    resolved_config_schema_path = config_schema_path or Path(
        os.environ.get(
            "MIGRATION_UNIFIED_SCHEMA_PATH",
            "/root/schema/workflowMigration.schema.json",
        )
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        if coordinator is not None:
            await coordinator.start()
        try:
            yield
        finally:
            if coordinator is not None:
                await coordinator.stop()
            if operations is not None and hasattr(operations, "shutdown"):
                operations.shutdown()
            if logs is not None and hasattr(logs, "shutdown"):
                logs.shutdown()

    app = FastAPI(
        title="Workflow Manage API",
        version="1.0.0",
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        lifespan=lifespan,
    )

    @app.get(
        "/api/v1/system/health",
        response_model=HealthV1,
        tags=["system"],
    )
    async def health() -> HealthV1:
        return HealthV1()

    @app.get(
        "/api/v1/manage/state",
        response_model=ManageSnapshotV1,
        tags=["manage"],
    )
    async def manage_state() -> ManageSnapshotV1:
        if coordinator is None:
            raise HTTPException(
                status_code=503,
                detail=OBSERVATION_NOT_CONFIGURED,
            )
        try:
            observation = await coordinator.get_observation()
        except Exception as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        _reconcile_operations(operations, observation.snapshot)
        return ManageSnapshotV1.from_domain(
            observation.snapshot,
            stale=observation.stale,
            refresh_error=(
                observation.refresh_error.to_dict()
                if observation.refresh_error else None
            ),
        )

    @app.get(
        "/api/v1/nodes/{node_id}/runtime-status",
        response_model=RuntimeStatusV1,
        response_model_exclude_none=True,
        tags=["manage"],
    )
    async def node_runtime_status(
        node_id: str,
        force: bool = False,
    ) -> RuntimeStatusV1:
        if coordinator is None:
            raise HTTPException(
                status_code=503,
                detail=OBSERVATION_NOT_CONFIGURED,
            )
        if runtime_status is None:
            raise HTTPException(
                status_code=503,
                detail="Runtime status is not configured",
            )
        observation = await coordinator.get_observation()
        node = observation.snapshot.nodes.get(node_id)
        if (
            node is None
            or not node.resource_plural
            or not node.resource_name
        ):
            raise HTTPException(
                status_code=404,
                detail="Runtime status is not available for this item.",
            )
        try:
            status = await asyncio.to_thread(
                runtime_status.inspect,
                node.id,
                node.resource_plural,
                node.resource_name,
                force=force,
                resource_phase=node.phase,
            )
            return RuntimeStatusV1.from_domain(status)
        except RuntimeStatusUnavailable as error:
            raise HTTPException(status_code=404, detail=str(error)) from error
        except Exception as error:
            logger.exception("Failed to inspect runtime status")
            raise HTTPException(status_code=502, detail=str(error)) from error

    def external_resource_service():
        if external_resources is None:
            raise HTTPException(
                status_code=503,
                detail="External resource access is not configured",
            )
        return external_resources

    def document_service():
        if config_documents is None:
            raise HTTPException(
                status_code=503,
                detail="Configuration documents are not configured",
            )
        return config_documents

    def diagnostics_service():
        if config_diagnostics is None:
            raise HTTPException(
                status_code=503,
                detail="Configuration diagnostics are not configured",
            )
        return config_diagnostics

    def connectivity_service():
        if connectivity is None:
            raise HTTPException(
                status_code=503,
                detail="Configuration connectivity checks are not configured",
            )
        return connectivity

    def submission_service():
        service = document_service()
        if not hasattr(service, "prepare"):
            raise HTTPException(
                status_code=503,
                detail="Configuration submission is not configured",
            )
        return service

    def _document_conflict(
        error: ConfigurationDocumentConflict,
    ) -> HTTPException:
        return HTTPException(
            status_code=409,
            detail={
                "code": "persisted_revision_conflict",
                "message": str(error),
                "current": ConfigurationDocumentV1.from_domain(
                    error.current
                ).model_dump(
                    by_alias=True,
                    exclude_none=True,
                    mode="json",
                ),
            },
        )

    def output_service():
        if outputs is None:
            raise HTTPException(
                status_code=503,
                detail="Managed output is not configured",
            )
        return outputs

    def operation_service():
        if operations is None:
            raise HTTPException(
                status_code=503,
                detail="Operation tracking is not configured",
            )
        return operations

    def approval_service():
        if approvals is None:
            raise HTTPException(
                status_code=503,
                detail="Approval actions are not configured",
            )
        return approvals

    def reset_service():
        if resets is None:
            raise HTTPException(
                status_code=503,
                detail="Reset actions are not configured",
            )
        return resets

    def log_service():
        if logs is None:
            raise HTTPException(
                status_code=503,
                detail="Managed logs are not configured",
            )
        return logs

    @app.get(
        "/api/v1/outputs",
        response_model=OutputInventoryV1,
        response_model_exclude_none=True,
        tags=["outputs"],
    )
    def list_outputs(
        target_id: Annotated[str, Query(alias="targetId")],
    ) -> OutputInventoryV1:
        try:
            return OutputInventoryV1.from_domain(
                output_service().list_outputs(target_id)
            )
        except OutputUnavailable as error:
            raise _output_error(404, "output_unavailable", error) from error

    @app.get(
        "/api/v1/outputs/content",
        response_model=OutputContentV1,
        response_model_exclude_none=True,
        tags=["outputs"],
    )
    def read_output(
        output_id: Annotated[str, Query(alias="outputId")],
    ) -> OutputContentV1:
        try:
            return OutputContentV1.from_domain(
                output_service().read_output(output_id)
            )
        except OutputStale as error:
            raise _output_error(409, "output_stale", error) from error
        except OutputUnavailable as error:
            raise _output_error(404, "output_unavailable", error) from error
        except OutputReadFailed as error:
            raise _output_error(502, "output_read_failed", error) from error

    @app.get(
        "/api/v1/outputs/download",
        tags=["outputs"],
        responses={
            200: {
                "content": {
                    "text/plain": {},
                    "application/json": {},
                    "application/yaml": {},
                },
                "description": "Complete managed output download",
            },
        },
    )
    def download_output(
        output_id: Annotated[str, Query(alias="outputId")],
    ) -> Response:
        try:
            descriptor, content = output_service().download_output(output_id)
        except OutputStale as error:
            raise _output_error(409, "output_stale", error) from error
        except OutputUnavailable as error:
            raise _output_error(404, "output_unavailable", error) from error
        except OutputReadFailed as error:
            raise _output_error(502, "output_read_failed", error) from error
        filename = (
            f"{descriptor.resource_name}-{descriptor.output_name}"
            f"{_content_extension(descriptor.content_type)}"
        )
        return Response(
            content=content,
            media_type=descriptor.content_type,
            headers={
                "Content-Disposition": f'attachment; filename="{filename}"',
            },
        )

    @app.get(
        "/api/v1/nodes/{node_id}/log-targets",
        response_model=LogTargetInventoryV1,
        response_model_exclude_none=True,
        tags=["logs"],
    )
    async def list_log_targets(node_id: str) -> LogTargetInventoryV1:
        if coordinator is None:
            raise HTTPException(
                status_code=503,
                detail=OBSERVATION_NOT_CONFIGURED,
            )
        observation = await coordinator.get_observation()
        node = observation.snapshot.nodes.get(node_id)
        capability = next(
            (
                item for item in (node.capabilities if node else ())
                if item.kind == "logs"
            ),
            None,
        )
        if capability is None:
            raise _log_error(
                404,
                "logs_unavailable",
                "Logs are not available for this item.",
            )
        try:
            resource_inventory = await asyncio.to_thread(
                log_service().list_targets,
                node_id,
                capability.target_id,
            )
            failure_targets = []
            aggregate_target_ids = []
            for child_id in node.child_ids:
                child = observation.snapshot.nodes.get(child_id)
                if child is None or child.status != "error":
                    continue
                child_capability = next(
                    (
                        item for item in child.capabilities
                        if item.kind == "logs"
                    ),
                    None,
                )
                if child_capability is None:
                    continue
                try:
                    child_inventory = await asyncio.to_thread(
                        log_service().list_targets,
                        child.id,
                        child_capability.target_id,
                    )
                except (LogUnavailable, LogTargetStale):
                    continue
                child_scope = next(
                    (
                        target for target in child_inventory.targets
                        if target.kind == "aggregate"
                    ),
                    None,
                )
                aggregate_target_ids.extend(
                    [child_scope.id]
                    if child_scope is not None
                    else [target.id for target in child_inventory.targets]
                )
                failure_targets.extend(
                    replace(
                        target,
                        label=f"Failure: {child.label} / {target.label}",
                    )
                    for target in sorted(
                        child_inventory.targets,
                        key=lambda target: (
                            target.container != "main",
                            target.label,
                        ),
                    )
                )
            if failure_targets:
                application_scope = next(
                    (
                        target for target in resource_inventory.targets
                        if target.kind == "aggregate"
                    ),
                    None,
                )
                aggregate_target_ids.extend(
                    [application_scope.id]
                    if application_scope is not None
                    else [
                        target.id
                        for target in resource_inventory.targets
                    ]
                )
                all_sources = await asyncio.to_thread(
                    log_service().combine_targets,
                    aggregate_target_ids,
                    label="All sources",
                )
                application_targets = tuple(
                    replace(
                        target,
                        label=f"Application / {target.label}",
                    )
                    for target in resource_inventory.targets
                )
                inventory = LogTargetInventory(
                    node_id=node_id,
                    capability_target_id=capability.target_id,
                    targets=(
                        all_sources,
                        *failure_targets,
                        *application_targets,
                    ),
                    message=(
                        "Workflow-step and application containers are "
                        "shown together by default. Select a target to "
                        "narrow the view."
                    ),
                )
            else:
                inventory = resource_inventory
            return LogTargetInventoryV1.from_domain(
                inventory,
                subject_label=node.label,
                subject_kind=node.kind,
                external_logs_url=external_logs_url,
            )
        except (LogUnavailable, LogTargetStale) as error:
            raise _log_error(404, "logs_unavailable", error) from error
        except Exception as error:
            logger.exception("Failed to resolve log targets")
            raise _log_error(502, "logs_read_failed", error) from error

    @app.post(
        "/api/v1/log-streams",
        response_model=LogStreamV1,
        response_model_exclude_none=True,
        status_code=201,
        tags=["logs"],
    )
    async def start_log_stream(
        request_body: StartLogStreamRequestV1,
    ) -> LogStreamV1:
        try:
            stream = await asyncio.to_thread(
                log_service().start,
                request_body.target_id,
                tail_lines=request_body.tail_lines,
                follow=request_body.follow,
                page_size=request_body.page_size,
            )
            return LogStreamV1.from_domain(stream)
        except LogTargetStale as error:
            raise _log_error(409, "log_target_stale", error) from error
        except LogUnavailable as error:
            raise _log_error(404, "logs_unavailable", error) from error
        except Exception as error:
            logger.exception("Failed to start log stream")
            raise _log_error(502, "logs_read_failed", error) from error

    @app.get(
        "/api/v1/log-streams/{stream_id}/pages",
        response_model=LogPageV1,
        response_model_exclude_none=True,
        tags=["logs"],
    )
    async def read_log_page(
        stream_id: str,
        before: Annotated[Optional[str], Query()] = None,
        after: Annotated[Optional[str], Query()] = None,
        limit: Annotated[int, Query(ge=1, le=1000)] = 200,
    ) -> LogPageV1:
        try:
            page = await asyncio.to_thread(
                log_service().page,
                stream_id,
                before=before,
                after=after,
                limit=limit,
            )
            return LogPageV1.from_domain(page)
        except LogUnavailable as error:
            raise _log_error(404, "logs_unavailable", error) from error

    @app.get(
        "/api/v1/log-streams/{stream_id}/events",
        tags=["logs"],
        responses={
            200: {
                "content": {SSE_MEDIA_TYPE: {}},
                "description": "Cancellable log event stream",
            },
        },
    )
    async def stream_log_events(
        request: Request,
        stream_id: str,
        after: Annotated[int, Query(ge=0)] = 0,
        last_event_id: Annotated[
            Optional[str],
            Header(alias="Last-Event-ID"),
        ] = None,
    ) -> StreamingResponse:
        cursor = max(after, _event_id(last_event_id))
        try:
            log_service().status(stream_id)
        except LogUnavailable as error:
            raise _log_error(404, "logs_unavailable", error) from error

        async def event_stream() -> AsyncIterator[str]:
            nonlocal cursor
            while not await request.is_disconnected():
                events = await asyncio.to_thread(
                    log_service().wait_for_events,
                    stream_id,
                    after_sequence=cursor,
                    timeout=10,
                )
                for event in events:
                    cursor = event.sequence
                    yield _encode_log_sse(event)
                status = log_service().status(stream_id)
                if status.state in ("ended", "stopped", "error"):
                    yield _encode_log_status_sse(status)
                    break
                if not events:
                    yield ": heartbeat\n\n"

        return StreamingResponse(
            event_stream(),
            media_type=SSE_MEDIA_TYPE,
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    @app.delete(
        "/api/v1/log-streams/{stream_id}",
        response_model=LogStreamStatusV1,
        response_model_exclude_none=True,
        tags=["logs"],
    )
    async def stop_log_stream(stream_id: str) -> LogStreamStatusV1:
        try:
            status = await asyncio.to_thread(
                log_service().stop,
                stream_id,
            )
            return LogStreamStatusV1.from_domain(status)
        except LogUnavailable as error:
            raise _log_error(404, "logs_unavailable", error) from error

    @app.get(
        "/api/v1/config/document",
        response_model=ConfigurationDocumentV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def load_config_document() -> ConfigurationDocumentV1:
        try:
            return ConfigurationDocumentV1.from_domain(
                document_service().load()
            )
        except HTTPException:
            raise
        except Exception as error:
            logger.exception("Failed to load the workflow configuration document")
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "configuration_document_unavailable",
                    "message": str(error) or type(error).__name__,
                },
            ) from error

    @app.get(
        "/api/v1/config/schema",
        response_model=ConfigurationSchemaV1,
        tags=["configuration"],
    )
    def load_config_schema() -> ConfigurationSchemaV1:
        try:
            schema = json.loads(
                resolved_config_schema_path.read_text(encoding="utf-8")
            )
            if not isinstance(schema, dict):
                raise ValueError("The unified configuration schema is not an object")
            return ConfigurationSchemaV1(unified_schema=schema)
        except Exception as error:
            logger.exception("Failed to load the unified configuration schema")
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "configuration_schema_unavailable",
                    "message": str(error) or type(error).__name__,
                },
            ) from error

    @app.put(
        "/api/v1/config/document",
        response_model=ConfigurationDocumentV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    async def save_config_document(
        request_body: SaveConfigurationDocumentRequestV1,
    ) -> ConfigurationDocumentV1:
        try:
            document = await asyncio.to_thread(
                document_service().save,
                request_body.expected_persisted_revision,
                request_body.raw_yaml,
            )
        except ConfigurationDocumentConflict as error:
            raise _document_conflict(error) from error
        except ValueError as error:
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "configuration_document_invalid",
                    "message": str(error),
                },
            ) from error
        except HTTPException:
            raise
        except Exception as error:
            logger.exception("Failed to save the workflow configuration document")
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "configuration_document_unavailable",
                    "message": str(error) or type(error).__name__,
                },
            ) from error
        if coordinator is not None:
            coordinator.invalidate_saved_configuration(
                document.persisted_revision
            )
        return ConfigurationDocumentV1.from_domain(document)

    @app.post(
        "/api/v1/config/diagnostics",
        response_model=ConfigEnvironmentDiagnosticsV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    async def diagnose_config_environment(
        request_body: ConfigEnvironmentDiagnosticsRequestV1,
    ) -> ConfigEnvironmentDiagnosticsV1:
        try:
            result = await asyncio.to_thread(
                diagnostics_service().diagnose_external_resources,
                request_body.raw_yaml,
            )
        except HTTPException:
            raise
        except Exception as error:
            logger.exception("Failed to diagnose configuration environment references")
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "configuration_diagnostics_unavailable",
                    "message": str(error) or type(error).__name__,
                },
            ) from error
        return ConfigEnvironmentDiagnosticsV1(
            draft_nonce=request_body.draft_nonce,
            status=result["status"],
            diagnostics=result.get("diagnostics") or [],
        )

    @app.post(
        "/api/v1/config/connectivity/inventory",
        response_model=ConnectivityInventoryV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def connectivity_inventory(
        request_body: ConnectivityInventoryRequestV1,
    ) -> ConnectivityInventoryV1:
        try:
            prepared = connectivity_service().prepare(
                request_body.raw_yaml,
                request_body.config_nonce,
            )
        except ValueError as error:
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "connectivity_config_invalid",
                    "message": (
                        "Connectivity checks require a valid configuration. "
                        f"{error}"
                    ),
                },
            ) from error
        return ConnectivityInventoryV1(
            config_nonce=prepared.config_nonce,
            targets=[
                ConnectivityTargetV1.model_validate(target.inventory_dict())
                for target in prepared.targets
            ],
        )

    @app.post(
        "/api/v1/config/connectivity/checks",
        response_model=OperationV1,
        response_model_exclude_none=True,
        status_code=202,
        tags=["configuration"],
    )
    def start_connectivity_checks(
        request_body: StartConnectivityChecksRequestV1,
    ) -> OperationV1:
        try:
            prepared = connectivity_service().prepare(
                request_body.raw_yaml,
                request_body.config_nonce,
            )
            available_ids = {target.id for target in prepared.targets}
            missing = sorted(set(request_body.target_ids) - available_ids)
            if missing:
                raise ValueError(
                    "Connectivity target was not found in the current "
                    "configuration: " + ", ".join(missing)
                )
        except ValueError as error:
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "connectivity_config_invalid",
                    "message": str(error),
                },
            ) from error

        selected_ids = tuple(
            request_body.target_ids
            or [target.id for target in prepared.targets]
        )

        def worker() -> OperationWorkResult:
            result = connectivity_service().run(
                prepared,
                request_body.target_ids,
            )
            return OperationWorkResult(
                waiting=False,
                message=str(result.get("summary") or "Checks completed"),
                result=result,
            )

        operation = operation_service().start(
            kind="connectivity-check",
            label=(
                f"Check {selected_ids[0]}"
                if len(selected_ids) == 1
                else f"Check {len(selected_ids)} configured connections"
            ),
            target_ids=selected_ids,
            worker=worker,
        )
        return OperationV1.from_domain(operation)

    @app.post(
        "/api/v1/config/review",
        response_model=ConfigReviewV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    async def review_config(
        request_body: PersistedRevisionRequestV1,
    ) -> ConfigReviewV1:
        snapshot = None
        if coordinator is not None:
            try:
                snapshot = (await coordinator.get_observation()).snapshot
            except Exception:
                snapshot = None
        try:
            review = submission_service().review(
                request_body.expected_persisted_revision,
                snapshot,
            )
        except ConfigurationDocumentConflict as error:
            raise _document_conflict(error) from error
        return ConfigReviewV1.from_domain(review)

    @app.post(
        "/api/v1/config/submit",
        response_model=OperationV1,
        response_model_exclude_none=True,
        status_code=202,
        tags=["configuration", "operations"],
    )
    async def submit_config(
        request_body: PersistedRevisionRequestV1,
    ) -> OperationV1:
        baseline_revision = ""
        snapshot = None
        if coordinator is not None:
            try:
                snapshot = (await coordinator.get_observation()).snapshot
                baseline_revision = snapshot.revision
            except Exception:
                snapshot = None
        try:
            review = ConfigReviewV1.from_domain(submission_service().review(
                request_body.expected_persisted_revision,
                snapshot,
            ))
            if not review.valid:
                raise ValueError(
                    "Configuration cannot be submitted until validation "
                    "errors are resolved."
                )
            submission_service().prepare(
                request_body.expected_persisted_revision
            )
        except ConfigurationDocumentConflict as error:
            raise _document_conflict(error) from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error

        def submit_worker() -> OperationWorkResult:
            prepared = submission_service().prepare(
                request_body.expected_persisted_revision
            )
            result = submission_service().submit(prepared, workflow_name)
            submitted_name = str(
                (result or {}).get("workflow_name") or workflow_name
            )
            return OperationWorkResult(
                waiting=True,
                message=(
                    "Workflow accepted; waiting for refreshed cluster state"
                ),
                result={
                    "workflowName": submitted_name,
                    "baselineRevision": baseline_revision,
                },
            )

        operation = operation_service().start(
            kind="submit",
            label="Submit workflow configuration",
            target_ids=tuple(
                dict.fromkeys(
                    change.resource_id
                    for change in review.changes
                    if change.resource_id
                )
            ),
            worker=submit_worker,
        )
        return OperationV1.from_domain(operation)

    @app.post(
        "/api/v1/config/preflight",
        response_model=AdmissionPreflightV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def preflight_config(
        request_body: PersistedRevisionRequestV1,
    ) -> AdmissionPreflightV1:
        try:
            report = submission_service().preflight(
                request_body.expected_persisted_revision,
                workflow_name,
            )
        except ConfigurationDocumentConflict as error:
            raise _document_conflict(error) from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        except RuntimeError as error:
            logger.warning("Admission preflight preparation failed: %s", error)
            raise HTTPException(
                status_code=502,
                detail={
                    "code": "admission_preflight_unavailable",
                    "message": (
                        "Admission preflight could not prepare the workflow: "
                        f"{error}"
                    ),
                },
            ) from error
        return AdmissionPreflightV1.from_domain(report)

    @app.get(
        "/api/v1/operations",
        response_model=OperationListV1,
        response_model_exclude_none=True,
        tags=["operations"],
    )
    def list_operations() -> OperationListV1:
        return OperationListV1(
            operations=[
                OperationV1.from_domain(operation)
                for operation in operation_service().list()
            ],
        )

    @app.get(
        "/api/v1/approval-gates",
        response_model=ApprovalGateInventoryV1,
        response_model_exclude_none=True,
        tags=["approvals"],
    )
    def list_approval_gates() -> ApprovalGateInventoryV1:
        return ApprovalGateInventoryV1.from_domain(
            approval_service().inventory()
        )

    @app.patch(
        "/api/v1/approval-gates/{gate_name}",
        response_model=SetPreapprovalResponseV1,
        tags=["approvals"],
    )
    def set_preapproval(
        gate_name: str,
        request_body: SetPreapprovalRequestV1,
    ) -> SetPreapprovalResponseV1:
        try:
            approval_service().set_preapproval(
                gate_name,
                expected_gate_revision=(
                    request_body.expected_gate_revision
                ),
                preapproved=request_body.preapproved,
            )
        except ApprovalUnavailable as error:
            raise _action_error(
                409,
                "preapproval_unavailable",
                error,
            ) from error
        except ApprovalStale as error:
            raise _action_error(
                409,
                "approval_stale",
                error,
            ) from error
        return SetPreapprovalResponseV1(
            gate_name=gate_name,
            preapproved=request_body.preapproved,
        )

    @app.get(
        "/api/v1/approvals/review",
        response_model=ApprovalReviewV1,
        response_model_exclude_none=True,
        tags=["approvals"],
    )
    async def review_approval(
        target_id: Annotated[str, Query(alias="targetId")],
    ) -> ApprovalReviewV1:
        snapshot_revision = None
        if coordinator is not None:
            try:
                snapshot_revision = (
                    await coordinator.get_observation()
                ).snapshot.revision
            except Exception:
                snapshot_revision = None
        try:
            return ApprovalReviewV1.from_domain(
                approval_service().review(
                    target_id,
                    snapshot_revision=snapshot_revision,
                )
            )
        except ApprovalUnavailable as error:
            raise _action_error(
                404,
                "approval_unavailable",
                error,
            ) from error

    @app.post(
        "/api/v1/approvals",
        response_model=OperationV1,
        response_model_exclude_none=True,
        status_code=202,
        tags=["approvals", "operations"],
    )
    async def approve(
        request_body: ApproveRequestV1,
    ) -> OperationV1:
        try:
            review = approval_service().validate(
                request_body.target_id,
                request_body.expected_gate_revision,
            )
        except ApprovalUnavailable as error:
            raise _action_error(
                404,
                "approval_unavailable",
                error,
            ) from error
        except ApprovalStale as error:
            raise _action_error(409, "approval_stale", error) from error

        baseline_revision = review.snapshot_revision or ""
        if coordinator is not None:
            try:
                baseline_revision = (
                    await coordinator.get_observation()
                ).snapshot.revision
            except Exception:
                pass

        def approval_worker() -> OperationWorkResult:
            accepted = approval_service().approve(
                review.target_id,
                review.gate_revision,
            )
            return OperationWorkResult(
                waiting=True,
                message=(
                    "Approval accepted; waiting for workflow reconciliation"
                ),
                result={
                    "approvalTargetId": accepted.target_id,
                    "gateName": accepted.gate_name,
                    "baselineRevision": baseline_revision,
                },
            )

        operation = operation_service().start(
            kind="approve",
            label=f"Approve {review.stage}",
            target_ids=(
                (review.resource_id,)
                if review.resource_id else ()
            ),
            worker=approval_worker,
        )
        return OperationV1.from_domain(operation)

    @app.post(
        "/api/v1/resets/plan",
        response_model=ResetPlanV1,
        response_model_exclude_none=True,
        tags=["resets"],
    )
    def plan_reset(
        request_body: ResetPlanRequestV1,
    ) -> ResetPlanV1:
        try:
            target_ids = list(request_body.target_ids)
            if request_body.target_id:
                target_ids.insert(0, request_body.target_id)
            target_ids = list(dict.fromkeys(target_ids))
            if not target_ids:
                raise ResetUnavailable(
                    "At least one reset target is required."
                )
            service = reset_service()
            plan = (
                service.plan_many(target_ids)
                if len(target_ids) > 1
                else service.plan(target_ids[0])
            )
            return ResetPlanV1.from_domain(
                plan
            )
        except ResetUnavailable as error:
            raise _action_error(
                400,
                "reset_unavailable",
                error,
            ) from error

    @app.post(
        "/api/v1/resets",
        response_model=OperationV1,
        response_model_exclude_none=True,
        status_code=202,
        tags=["resets", "operations"],
    )
    async def execute_reset(
        request_body: ExecuteResetRequestV1,
    ) -> OperationV1:
        try:
            plan = reset_service().validate(request_body.plan_token)
        except ResetPlanStale as error:
            raise _action_error(409, "reset_plan_stale", error) from error

        resubmit = request_body.resubmit or bool(request_body.approvals)
        if resubmit:
            submission_service()
        if request_body.expected_persisted_revision:
            if not resubmit:
                raise HTTPException(
                    status_code=400,
                    detail=(
                        "A saved configuration revision is only valid for "
                        "reset and "
                        "resubmit."
                    ),
                )
            try:
                submission_service().prepare(
                    request_body.expected_persisted_revision
                )
            except ConfigurationDocumentConflict as error:
                raise _document_conflict(error) from error
            except ValueError as error:
                raise HTTPException(
                    status_code=400,
                    detail=str(error),
                ) from error
        elif resubmit:
            raise HTTPException(
                status_code=400,
                detail=(
                    "Reset and resubmit requires the saved configuration "
                    "revision that was reviewed."
                ),
            )

        baseline_revision = ""
        if coordinator is not None and resubmit:
            try:
                baseline_revision = (
                    await coordinator.get_observation()
                ).snapshot.revision
            except Exception:
                pass

        def reset_worker() -> OperationWorkResult:
            prepared = None
            if resubmit:
                prepared = submission_service().prepare(
                    request_body.expected_persisted_revision
                )
            result = reset_service().execute(request_body.plan_token)
            operation_result = {
                "planToken": result.plan.token,
                "targetCount": len(result.plan.targets),
            }
            submission = None
            if resubmit:
                assert prepared is not None
                submission = submission_service().submit(
                    prepared,
                    workflow_name,
                )
                submitted_name = str(
                    (submission or {}).get("workflow_name")
                    or workflow_name
                )
                operation_result.update({
                    "workflowName": submitted_name,
                    "baselineRevision": baseline_revision,
                })
            return OperationWorkResult(
                waiting=resubmit,
                message=(
                    "Reset completed and configuration submitted; "
                    "waiting for the replacement workflow"
                    if resubmit else result.message
                ),
                detail=result.detail,
                result=operation_result,
            )

        if resubmit:
            label = (
                f"Reset and resubmit {plan.targets[0].path}"
                if len(plan.targets) == 1
                else f"Reset and resubmit {len(plan.targets)} resources"
            )
        else:
            label = (
                f"Reset {plan.targets[0].path}"
                if len(plan.targets) == 1
                else f"Reset {len(plan.targets)} resources"
            )
        operation = operation_service().start(
            kind="reset",
            label=label,
            target_ids=tuple(
                f"resource:{target.plural}:{target.name}"
                for target in plan.targets
            ),
            worker=reset_worker,
        )
        return OperationV1.from_domain(operation)

    @app.get(
        "/api/v1/operations/events",
        tags=["operations"],
        responses={
            200: {
                "content": {SSE_MEDIA_TYPE: {}},
                "description": "Tracked operation event stream",
            },
        },
    )
    async def operation_events(
        request: Request,
        last_event_id: Optional[str] = Header(default=None),
    ) -> StreamingResponse:
        manager = operation_service()
        cursor = _event_id(last_event_id)

        async def event_stream() -> AsyncIterator[str]:
            async for event in manager.stream_events(cursor):
                if await request.is_disconnected():
                    break
                if event is None:
                    yield "event: heartbeat\ndata: {}\n\n"
                else:
                    yield _encode_operation_sse(event)

        return StreamingResponse(
            event_stream(),
            media_type=SSE_MEDIA_TYPE,
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post(
        "/api/v1/external-resources",
        response_model=ExternalResourceInventoryV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def list_external_resources(
        request_body: ExternalResourceContextRequestV1,
    ) -> ExternalResourceInventoryV1:
        try:
            inventory = external_resource_service().list(
                request_body.raw_yaml,
                request_body.node_id,
            )
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return ExternalResourceInventoryV1.from_domain(inventory)

    @app.post(
        "/api/v1/external-resources/select",
        response_model=ExternalResourceSelectionV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def select_external_resource(
        request_body: SelectExternalResourceRequestV1,
    ) -> ExternalResourceSelectionV1:
        try:
            external_resource_service().validate_selection(
                raw_yaml=request_body.raw_yaml,
                node_id=request_body.node_id,
                name=request_body.name,
                kind=request_body.kind,
                group=request_body.group,
                key=request_body.key,
                accept_warning=request_body.accept_warning,
                manual=request_body.manual,
            )
        except ExternalResourceSelectionWarning as error:
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "external_selection_warning",
                    "message": error.message,
                },
            ) from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return ExternalResourceSelectionV1()

    @app.post(
        "/api/v1/external-resources/details",
        response_model=ExternalResourceDetailsV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def external_resource_details(
        request_body: ExternalResourceDetailsRequestV1,
    ) -> ExternalResourceDetailsV1:
        try:
            details = external_resource_service().read(
                request_body.raw_yaml,
                request_body.node_id,
                request_body.name,
            )
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return ExternalResourceDetailsV1.from_domain(details)

    @app.post(
        "/api/v1/external-resources/save",
        response_model=ExternalResourceMutationV1,
        response_model_exclude_none=True,
        tags=["configuration"],
    )
    def save_external_resource(
        request_body: SaveExternalResourceRequestV1,
    ) -> ExternalResourceMutationV1:
        try:
            mutation = external_resource_service().save(
                raw_yaml=request_body.raw_yaml,
                node_id=request_body.node_id,
                values=request_body.values,
                confirmations=request_body.confirmations,
                existing_name=request_body.existing_name,
            )
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return ExternalResourceMutationV1.from_domain(mutation)

    @app.get(
        "/api/v1/manage/events",
        tags=["manage"],
        responses={
            200: {
                "content": {SSE_MEDIA_TYPE: {}},
                "description": "Workflow state invalidation event stream",
            },
        },
    )
    async def manage_events(
        request: Request,
        last_event_id: Optional[str] = Header(default=None),
    ) -> StreamingResponse:
        if coordinator is None:
            raise HTTPException(
                status_code=503,
                detail=OBSERVATION_NOT_CONFIGURED,
            )
        cursor = _event_id(last_event_id)

        async def event_stream() -> AsyncIterator[str]:
            async for event in coordinator.stream_events(cursor):
                if await request.is_disconnected():
                    break
                yield _encode_sse(event)

        return StreamingResponse(
            event_stream(),
            media_type=SSE_MEDIA_TYPE,
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    bundle_dir = Path(static_dir) if static_dir is not None else DEFAULT_STATIC_DIR
    assets_dir = bundle_dir / "assets"
    if assets_dir.is_dir():
        app.mount(
            "/assets",
            StaticFiles(directory=assets_dir),
            name="manage-assets",
        )

    @app.get("/{client_path:path}", include_in_schema=False)
    async def spa_fallback(client_path: str):
        if client_path == "api" or client_path.startswith("api/"):
            raise HTTPException(status_code=404, detail="Not Found")
        index_file = bundle_dir / "index.html"
        if not index_file.is_file():
            raise HTTPException(
                status_code=503,
                detail=(
                    "Workflow Manage web assets are not installed. "
                    "Build and stage migrationConsole/web first."
                ),
            )
        return FileResponse(index_file, media_type="text/html")

    _register_contract_schemas(app)
    return app


def _event_id(value: Optional[str]) -> int:
    if not value:
        return 0
    try:
        return max(0, int(value))
    except ValueError:
        return 0


def _encode_sse(event: ObservationEvent) -> str:
    data = json.dumps(event.data, separators=(",", ":"), sort_keys=True)
    return f"id: {event.id}\nevent: {event.event}\ndata: {data}\n\n"


def _encode_operation_sse(event: OperationEvent) -> str:
    data = json.dumps(
        OperationV1.from_domain(event.operation).model_dump(
            by_alias=True,
            exclude_none=True,
            mode="json",
        ),
        separators=(",", ":"),
        sort_keys=True,
    )
    return (
        f"id: {event.id}\nevent: operation-updated\n"
        f"data: {data}\n\n"
    )


def _encode_log_sse(event: Any) -> str:
    data = json.dumps(
        LogEventV1.from_domain(event).model_dump(
            by_alias=True,
            exclude_none=True,
            mode="json",
        ),
        separators=(",", ":"),
        sort_keys=True,
    )
    return f"id: {event.sequence}\nevent: log\ndata: {data}\n\n"


def _encode_log_status_sse(status: Any) -> str:
    data = json.dumps(
        LogStreamStatusV1.from_domain(status).model_dump(
            by_alias=True,
            exclude_none=True,
            mode="json",
        ),
        separators=(",", ":"),
        sort_keys=True,
    )
    return f"event: stream-state\ndata: {data}\n\n"


def _register_contract_schemas(app: FastAPI) -> None:
    """Include contracts that are introduced before their resource routes."""
    default_openapi = app.openapi

    def contract_openapi() -> Dict[str, Any]:
        document = default_openapi()
        schemas = document.setdefault("components", {}).setdefault("schemas", {})
        snapshot_schema = ManageSnapshotV1.model_json_schema(
            ref_template="#/components/schemas/{model}",
        )
        definitions = snapshot_schema.pop("$defs", {})
        schemas.update(definitions)
        schemas["ManageSnapshotV1"] = snapshot_schema
        return document

    app.openapi = contract_openapi


app = create_app()


def _log_error(status: int, code: str, error: Any) -> HTTPException:
    return HTTPException(
        status_code=status,
        detail={
            "code": code,
            "message": str(error),
        },
    )


def _reconcile_operations(
    operations: Optional[Any],
    snapshot: Any,
) -> None:
    if operations is None:
        return
    workflow = snapshot.workflow
    operations.reconcile_submit(
        workflow_name=workflow.name if workflow else None,
        snapshot_revision=snapshot.revision,
        workflow_phase=workflow.phase if workflow else None,
    )
    active_approvals = tuple(
        capability.target_id
        for node in snapshot.nodes.values()
        for capability in node.capabilities
        if capability.kind == "approve"
    )
    operations.reconcile_approvals(
        active_target_ids=active_approvals,
        snapshot_revision=snapshot.revision,
    )


def _action_error(
    status_code: int,
    code: str,
    error: Exception,
) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={
            "code": code,
            "message": str(error) or type(error).__name__,
        },
    )


def _output_error(
    status_code: int,
    code: str,
    error: Exception,
) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={
            "code": code,
            "message": str(error) or type(error).__name__,
        },
    )


def _content_extension(content_type: str) -> str:
    if content_type == "application/json":
        return ".json"
    if content_type == "application/yaml":
        return ".yaml"
    return ".txt"
