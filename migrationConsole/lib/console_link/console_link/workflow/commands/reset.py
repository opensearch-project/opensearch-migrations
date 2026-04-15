"""Reset command for workflow CLI - deletes migration CRDs in dependency order.

Uses direct K8s API calls (not Argo workflows) to tear down migration resources.
Optionally includes capture proxy and Kafka infrastructure teardown.
"""

import logging
import click

from ..models.utils import ExitCode, load_k8s_config
from ..services.k8s_reset_service import K8sResetService

logger = logging.getLogger(__name__)


@click.command(name="reset")
@click.option(
    '--namespace',
    default='ma',
    help='Kubernetes namespace for the migration resources (default: ma)'
)
@click.option(
    '--include-proxy',
    is_flag=True,
    default=False,
    help='Also delete CapturedTraffic (proxy) and Kafka resources. '
         'Without this flag, only replay and snapshot resources are deleted.'
)
@click.option(
    '--resource-name',
    default=None,
    help='Delete only resources with this name. Without this flag, '
         'ALL instances of each CRD type are deleted.'
)
@click.option(
    '--yes', '-y',
    is_flag=True,
    default=False,
    help='Skip confirmation prompt'
)
@click.pass_context
def reset_command(ctx, namespace, include_proxy, resource_name, yes):
    """Reset migration resources by deleting CRDs in dependency order.

    Deletes migration CRDs in reverse dependency order:
      1. TrafficReplay
      2. SnapshotMigration
      3. DataSnapshot

    With --include-proxy, also deletes:
      4. CapturedTraffic (capture proxy)
      5. Kafka resources (KafkaTopic, KafkaNodePool, Kafka cluster)

    Kafka deletion is protected by a ValidatingAdmissionPolicy that requires
    a teardown-approval annotation. This command stamps the annotation
    automatically before deleting.

    All deletes are idempotent — already-deleted resources are silently skipped.

    Examples:
        workflow reset                          # Delete replay + snapshot CRDs
        workflow reset --include-proxy          # Delete everything including proxy + Kafka
        workflow reset --resource-name my-migration   # Delete specific named resources
        workflow reset --include-proxy -y       # Skip confirmation
    """
    # Show what will be deleted
    resources_to_delete = ["TrafficReplay", "SnapshotMigration", "DataSnapshot"]
    if include_proxy:
        resources_to_delete.extend([
            "CapturedTraffic (proxy)",
            "KafkaTopic", "KafkaNodePool", "Kafka (cluster)"
        ])

    scope = f"named '{resource_name}'" if resource_name else "ALL instances"

    click.echo(f"Reset will delete {scope} of the following resources in namespace '{namespace}':")
    for r in resources_to_delete:
        click.echo(f"  - {r}")

    if include_proxy:
        click.echo()
        click.echo("⚠  --include-proxy is set: this will tear down Kafka infrastructure!")
        click.echo("   Kafka resources will be annotated for teardown approval before deletion.")

    # Confirm unless --yes
    if not yes:
        click.echo()
        if not click.confirm("Proceed with reset?"):
            click.echo("Aborted.")
            ctx.exit(ExitCode.SUCCESS.value)
            return

    # Initialize K8s
    try:
        load_k8s_config()
    except Exception as e:
        click.echo(
            f"Error: Could not load Kubernetes configuration.\n"
            f"Make sure kubectl is configured or you're running inside a cluster.\n"
            f"Details: {e}",
            err=True
        )
        ctx.exit(ExitCode.FAILURE.value)
        return

    # Run the reset
    try:
        service = K8sResetService(namespace=namespace)
        result = service.reset(
            include_proxy=include_proxy,
            resource_name=resource_name,
        )

        # Print per-resource results
        click.echo()
        for r in result.results:
            icon = "✓" if r.success else "✗"
            click.echo(f"  {icon} {r.message}")

        click.echo()
        if result.success:
            click.echo(f"Reset complete ({result.summary})")
        else:
            click.echo(f"Reset completed with errors ({result.summary})", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error during reset: {e}", err=True)
        logger.exception("Reset failed")
        ctx.exit(ExitCode.FAILURE.value)
