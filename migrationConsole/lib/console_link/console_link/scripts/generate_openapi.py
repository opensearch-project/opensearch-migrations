import json
import sys

from console_link.api.main import app

schema = app.openapi()

json.dump(schema, sys.stdout)
