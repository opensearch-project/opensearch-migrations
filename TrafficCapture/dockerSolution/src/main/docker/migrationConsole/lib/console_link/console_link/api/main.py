import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from console_link.api.system import system_router
from console_link.api.sessions import session_router
from console_link.api.snapshot import snapshot_router

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

session_router.include_router(snapshot_router, prefix="/{session_name}", tags=["snapshot"])

app.include_router(system_router)
app.include_router(session_router)
