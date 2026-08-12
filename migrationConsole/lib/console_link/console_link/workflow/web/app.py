"""FastAPI application factory for native workflow manage."""

from contextlib import asynccontextmanager
import json
from pathlib import Path
from typing import Any, AsyncIterator, Dict, Optional

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles

from ..application.observations import ObservationCoordinator, ObservationEvent
from .contracts import HealthV1, ManageSnapshotV1


DEFAULT_STATIC_DIR = Path(__file__).with_name("static")


def create_app(
    static_dir: Optional[Path] = None,
    coordinator: Optional[ObservationCoordinator] = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        if coordinator is not None:
            await coordinator.start()
        try:
            yield
        finally:
            if coordinator is not None:
                await coordinator.stop()

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
                detail="Workflow observation is not configured",
            )
        try:
            observation = await coordinator.get_observation()
        except Exception as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        return ManageSnapshotV1.from_domain(
            observation.snapshot,
            stale=observation.stale,
            refresh_error=(
                observation.refresh_error.to_dict()
                if observation.refresh_error else None
            ),
        )

    @app.get(
        "/api/v1/manage/events",
        tags=["manage"],
        responses={
            200: {
                "content": {"text/event-stream": {}},
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
                detail="Workflow observation is not configured",
            )
        cursor = _event_id(last_event_id)

        async def event_stream() -> AsyncIterator[str]:
            async for event in coordinator.stream_events(cursor):
                if await request.is_disconnected():
                    break
                yield _encode_sse(event)

        return StreamingResponse(
            event_stream(),
            media_type="text/event-stream",
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
