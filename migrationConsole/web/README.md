# Workflow Manage Web

This is the production React frontend for native `workflow manage`. FastAPI serves the
compiled bundle, `/api/v1/*`, API documentation, and event streams from one origin.
The browser only connects to this FastAPI port; it does not need direct access to Argo
or another cluster service.

## Run the packaged application

The migration-console image contains both the FastAPI implementation and the compiled
React bundle. After installing the Migration Assistant chart, start the server in the
pod and run a local port-forward with:

```sh
KUBE_CONTEXT=kind-ma ../../deployment/k8s/workflowWeb.sh start
```

Open `http://127.0.0.1:8000`. API documentation is available at
`http://127.0.0.1:8000/api/docs`.

`start` keeps the port-forward in the foreground. Ctrl-C stops the port-forward but
leaves the in-pod server running. Its lifecycle can be inspected separately:

```sh
KUBE_CONTEXT=kind-ma ../../deployment/k8s/workflowWeb.sh status
KUBE_CONTEXT=kind-ma ../../deployment/k8s/workflowWeb.sh logs
KUBE_CONTEXT=kind-ma ../../deployment/k8s/workflowWeb.sh logs --follow
KUBE_CONTEXT=kind-ma ../../deployment/k8s/workflowWeb.sh stop
```

Override the browser-facing port with `WORKFLOW_MANAGE_LOCAL_PORT` and the managed
workflow with `WORKFLOW_NAME`. The chart does not start this provisional server
automatically; `workflowWeb.sh` does so with `kubectl exec` until the web process has
a permanent deployment model.

## Build the packaged application

Use the Gradle-managed Node.js version:

```sh
../../gradlew :migrationConsole:buildAndStageManageWeb
```

`:migrationConsole:syncDockerBuildContext` copies the resulting bundle into
`console_link/workflow/web/static`. The migration-console image build depends on that
task, so the Python server and its matching frontend are packaged together. The helper
checks for the staged `index.html` before it starts the server.

## Frontend development

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
This two-port arrangement is only for source development and hot reload.

To exercise the compiled same-origin application against the current cluster:

```sh
workflow manage --web \
  --web-static-dir /path/to/repository/migrationConsole/web/dist
```

The image's staged application does not need `--web-static-dir`.

## Generated API client

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
