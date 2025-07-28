from console_link.api.main import app
from fastapi.openapi.utils import get_openapi
import json
import sys

schema = get_openapi(
    title=app.title,
    version=app.version,
    description=app.description,
    routes=app.routes,
)

json.dump(schema, sys.stdout)
