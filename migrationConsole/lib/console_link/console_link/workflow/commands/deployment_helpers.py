"""Shared helpers for pause/resume/scale commands to reduce per-command complexity."""

from typing import Callable, List, Optional, Tuple

import click

from ..models.utils import ExitCode
from ..services.deployment_service import DeploymentInfo, DeploymentService


def _format_status(dep: DeploymentInfo) -> str:
    return "paused" if dep.is_paused else f"running ({dep.replicas} replicas)"


def _show_available(deployments: List[DeploymentInfo]):
    click.echo("Available Deployments:")
    for d in deployments:
        click.echo(f"  - {d.display_name} ({_format_status(d)})")


def execute_and_report(targets: List[DeploymentInfo], action: Callable, verb: str):
    succeeded = 0
    for dep in targets:
        result = action(dep)
        symbol = "✓" if result["success"] else "✗"
        click.echo(f"  {symbol} {result['message']}")
        if result["success"]:
            succeeded += 1
    click.echo(f"\n{verb} {succeeded} of {len(targets)} Deployment(s).")


def resolve_targets(ctx, workflow_name: str, namespace: str, task_names: tuple,
                    empty_message: str,
                    no_match_message: str,
                    auto_filter: Optional[Callable] = None
                    ) -> Optional[Tuple[DeploymentService, List[DeploymentInfo]]]:
    """Discover and filter deployments. Returns (service, targets) or None on failure."""
    service = DeploymentService()
    deployments = service.discover_pausable_deployments(workflow_name, namespace)

    if not deployments:
        click.echo(empty_message)
        ctx.exit(ExitCode.FAILURE.value)
        return None

    targets = service.filter_by_task_names(deployments, task_names)
    if not task_names and auto_filter:
        targets = [d for d in targets if auto_filter(d)]

    if not targets:
        click.echo(no_match_message)
        if task_names:
            _show_available(deployments)
        ctx.exit(ExitCode.FAILURE.value)
        return None

    return service, targets


def confirm_if_needed(task_names: tuple, yes: bool):
    if not task_names and not yes:
        click.confirm("Proceed?", abort=True)
