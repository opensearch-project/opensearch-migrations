"""FastAPI application factory for native workflow manage."""

from pathlib import Path
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .contracts import HealthV1, ManageSnapshotV1


DEFAULT_STATIC_DIR = Path(__file__).with_name("static")


def create_app(static_dir: Optional[Path] = None) -> FastAPI:
    app = FastAPI(
        title="Workflow Manage API",
        version="1.0.0",
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
    )

    @app.get(
        "/api/v1/system/health",
        response_model=HealthV1,
        tags=["system"],
    )
    async def health() -> HealthV1:
        return HealthV1()

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
