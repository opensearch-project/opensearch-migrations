"""Production ASGI runner for the native workflow manage application."""

import argparse
from pathlib import Path
from typing import Optional, Sequence

import uvicorn

from ..application.manage_state import ManageStateService
from ..application.observations import ObservationCoordinator
from ..models.utils import load_k8s_config
from ..services.argo_observation_service import make_argo_observation_service
from ..services.config_edit_service import ConfigEditService
from .app import create_app


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
    load_k8s_config()
    state_service = ManageStateService(
        namespace=namespace,
        workflow_name=workflow_name,
        argo_service=make_argo_observation_service(
            argo_server,
            insecure,
            token,
        ),
        config_service_provider=lambda: ConfigEditService(namespace=namespace),
    )
    coordinator = ObservationCoordinator(
        state_service,
        refresh_interval=refresh_interval,
    )
    app = create_app(static_dir=static_dir, coordinator=coordinator)
    uvicorn.run(
        app,
        host=host,
        port=port,
        access_log=True,
    )


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", default="ma")
    parser.add_argument("--workflow-name", default="migration")
    parser.add_argument(
        "--argo-server",
        default="http://argo-server:2746",
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
