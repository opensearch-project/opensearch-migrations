import os
from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from console_link.api.custom_openapi import OpenApiWithNullables
from console_link.api.system import system_router
from console_link.api.backfill import backfill_router
from console_link.api.sessions import session_router
from console_link.api.snapshot import snapshot_router
from console_link.api.metadata import metadata_router
from console_link.api.clusters import clusters_router

app = FastAPI(
    title="Migration Assistant API",
    version="0.0.1",
    root_path=os.getenv("FASTAPI_ROOT_PATH", "")
)

origins = [
    # Enable development environments
    "http://127.0.0.1:3000",
    "http://localhost:3000",
    # Enable prod container environments
    "http://127.0.0.1:8080",
    "http://localhost:8080",
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

custom_openapi = OpenApiWithNullables(app)
app.openapi = custom_openapi.openapi_with_nullables


def add_to_session_router(router: APIRouter):
    session_router.include_router(router, prefix="/{session_name}")


add_to_session_router(snapshot_router)
add_to_session_router(metadata_router)
add_to_session_router(backfill_router)
add_to_session_router(clusters_router)

app.include_router(system_router)
app.include_router(session_router)
