import os
from fastapi import FastAPI
from console_link.api.system import system_router

app = FastAPI(
    title="Migration Assistant API",
    version="0.0.1",
    root_path=os.getenv("FASTAPI_ROOT_PATH", "")
)

app.include_router(system_router)
