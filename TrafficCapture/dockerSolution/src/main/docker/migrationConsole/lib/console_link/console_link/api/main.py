import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from console_link.api.system import system_router

app = FastAPI(
    title="Migration Assistant API",
    version="0.0.1",
    root_path=os.getenv("FASTAPI_ROOT_PATH", "")
)

origins = [
    # Enable development environments by default
    "http://127.0.0.1:3000",
    "http://localhost:3000",
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(system_router)
