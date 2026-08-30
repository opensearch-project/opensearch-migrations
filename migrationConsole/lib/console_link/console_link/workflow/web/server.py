"""Production ASGI runner for the native workflow manage application."""

import argparse
import os
from pathlib import Path
from typing import Optional, Sequence
from urllib.parse import quote

import requests
import uvicorn

from ..application.manage_state import ManageStateService
from ..application.observations import ObservationCoordinator
from ..application.config_drafts import ConfigDraftService
from ..application.outputs import OutputService
from ..application.logs import KubernetesLogSource, LogStreamService
from ..application.operations import OperationManager
from ..application.actions import ApprovalService
from ..application.resets import ResetService
from ..commands.argo_utils import DEFAULT_ARGO_SERVER_URL
from ..commands.autocomplete_workflows import DEFAULT_WORKFLOW_NAME
from ..models.secret_store import SecretStore
from ..models.workflow_config_store import WorkflowConfigStore
from ..resource_tree import build_resource_tree
from ..services.argo_observation_service import make_argo_observation_service
from ..services.config_edit_service import ConfigEditService
from ..services.script_runner import ScriptRunner
from .app import create_app
from .kubernetes import pin_kubernetes_runtime


def cloudwatch_log_group_url(
    region: Optional[str],
    log_group: Optional[str],
) -> Optional[str]:
    """Build a console deep link only for explicitly configured log storage."""
    if not region or not log_group:
        return None
    encoded_group = quote(log_group, safe="").replace("%", "$25")
    return (
        "https://console.aws.amazon.com/cloudwatch/home"
        f"?region={quote(region, safe='')}"
        f"#logsV2:log-groups/log-group/{encoded_group}"
    )


def load_workflow_for_approvals(
    argo_service,
    workflow_name: str,
    namespace: str,
):
    try:
        result, workflow = argo_service.get_workflow(
            workflow_name,
            namespace,
        )
    except requests.HTTPError as error:
        if error.response is not None and error.response.status_code == 404:
            return {}
        raise
    if not result.get("success"):
        raise RuntimeError(
            str(result.get("error") or "Workflow is unavailable")
        )
    return workflow


def run_server(
    *,
    namespace: str,
    workflow_name: str,
    argo_server: str,
    insecure: bool,
    token: Optional[str],
    host: str,
    port: int,
    static_dir: Optional[Path] = None,
    refresh_interval: float = 3.0,
) -> None:
    with pin_kubernetes_runtime() as k8s:
        script_runner = ScriptRunner(env=k8s.subprocess_env)
        script_runner.require_supported_nodejs()
        argo_service = make_argo_observation_service(
            argo_server,
            insecure,
            token,
        )
        config_service = ConfigEditService(
            namespace=namespace,
            store=WorkflowConfigStore(
                namespace=namespace,
                k8s_client=k8s.core_api,
            ),
            runner=script_runner,
            core_api=k8s.core_api,
            custom_api=k8s.custom_api,
            secret_store=SecretStore(
                namespace=namespace,
                default_labels={"use-case": "http-basic-credentials"},
                k8s_client=k8s.core_api,
            ),
        )
        state_service = ManageStateService(
            namespace=namespace,
            workflow_name=workflow_name,
            argo_service=argo_service,
            resource_loader=lambda target_namespace: build_resource_tree(
                target_namespace,
                k8s.custom_api,
            ),
            config_service_provider=lambda: config_service,
        )
        coordinator = ObservationCoordinator(
            state_service,
            refresh_interval=refresh_interval,
        )
        operation_manager = OperationManager()
        log_streams = LogStreamService(
            KubernetesLogSource(
                namespace=namespace,
                workflow_name=workflow_name,
                core_api=k8s.core_api,
                custom_api=k8s.custom_api,
            )
        )

        def load_workflow():
            return load_workflow_for_approvals(
                argo_service,
                workflow_name,
                namespace,
            )

        app = create_app(
            static_dir=static_dir,
            coordinator=coordinator,
            workflow_name=workflow_name,
            config_drafts=ConfigDraftService(config_service),
            outputs=OutputService(
                namespace=namespace,
                custom_api=k8s.custom_api,
            ),
            operations=operation_manager,
            approvals=ApprovalService(
                namespace=namespace,
                workflow_name=workflow_name,
                workflow_loader=load_workflow,
                custom_api=k8s.custom_api,
            ),
            resets=ResetService(
                namespace=namespace,
                custom_api=k8s.custom_api,
            ),
            logs=log_streams,
            external_logs_url=cloudwatch_log_group_url(
                os.environ.get("WORKFLOW_CLOUDWATCH_REGION"),
                os.environ.get("WORKFLOW_CLOUDWATCH_LOG_GROUP"),
            ),
        )
        uvicorn.run(
            app,
            host=host,
            port=port,
            access_log=True,
        )


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default="ma")
    parser.add_argument("--workflow-name", default=DEFAULT_WORKFLOW_NAME)
    parser.add_argument(
        "--argo-server",
        default=DEFAULT_ARGO_SERVER_URL,
    )
    parser.add_argument("--insecure", action="store_true", default=True)
    parser.add_argument("--token")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8000, type=int)
    parser.add_argument("--static-dir", type=Path)
    parser.add_argument("--refresh-interval", default=3.0, type=float)
    args = parser.parse_args(argv)
    run_server(
        namespace=args.namespace,
        workflow_name=args.workflow_name,
        argo_server=args.argo_server,
        insecure=args.insecure,
        token=args.token,
        host=args.host,
        port=args.port,
        static_dir=args.static_dir,
        refresh_interval=args.refresh_interval,
    )


if __name__ == "__main__":
    main()
