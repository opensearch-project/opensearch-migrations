# Workflow Manage Web

This is the production React frontend for native `workflow manage`. FastAPI serves the
compiled bundle and `/api/v1/*` from one origin.

Use the Gradle-managed Node.js version:

```sh
../../gradlew :migrationConsole:buildAndStageManageWeb
```

For frontend development, run the API and Vite in separate terminals:

```sh
cd migrationConsole/lib/console_link
.venv/bin/python -m console_link.workflow.web.server
```

```sh
cd migrationConsole/web
npm run dev
```

Vite listens on `http://127.0.0.1:5173` and proxies `/api` to port `8000`.

To exercise the compiled same-origin application against the current cluster:

```sh
workflow manage --web \
  --web-static-dir /path/to/repository/migrationConsole/web/dist
```

The image's staged application does not need `--web-static-dir`.

`openapi.json` and `src/api/schema.generated.ts` are local generated files and are not
checked into Git. Gradle treats the Python web contracts as inputs and keeps both files when
they are current, so repeat builds use the local outputs. Direct npm commands also generate
them when they are missing or older than the contract inputs.

Force regeneration after changing the FastAPI contract with:

```sh
npm run generate:api
```

Generation uses `migrationConsole/lib/console_link/.venv` when available, then an active
virtualenv, configured system Python, or Pipenv. Set `MANAGE_WEB_PYTHON` to select another
Python interpreter with the console-link dependencies installed.
