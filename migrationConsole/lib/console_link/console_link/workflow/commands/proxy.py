"""Proxy management commands for workflow CLI.

Operational commands that modify the running proxy configuration by reading
the denormalized config from the active Argo workflow, modifying it, and
resubmitting through the config processor with --transformed-config to
regenerate CRDs with correct dependsOn while preserving all existing
labels and denormalized state.
"""

import json
import logging
import os
import subprocess
import tempfile
import time

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config

logger = logging.getLogger(__name__)

ARGO_GROUP = 'argoproj.io'
ARGO_VERSION = 'v1alpha1'
DEFAULT_WORKFLOW_NAME = 'migration-workflow'


def _get_workflow_config(namespace):
    """Read the denormalized config from the running Argo workflow's parameters."""
    custom = client.CustomObjectsApi()
    wf = None
    try:
        wf = custom.get_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=DEFAULT_WORKFLOW_NAME
        )
    except ApiException as e:
        if e.status == 404:
            items = custom.list_namespaced_custom_object(
                group=ARGO_GROUP, version=ARGO_VERSION,
                namespace=namespace, plural='workflows'
            ).get('items', [])
            if items:
                wf = items[0]
        else:
            raise

    if not wf:
        return None, None
    wf_name = wf['metadata']['name']
    params = wf.get('spec', {}).get('arguments', {}).get('parameters', [])
    config_str = next((p['value'] for p in params if p['name'] == 'config'), None)
    if not config_str:
        return wf_name, None
    return wf_name, json.loads(config_str)


def _stop_and_delete_workflow(namespace, wf_name):
    """Stop and delete a specific Argo workflow."""
    custom = client.CustomObjectsApi()
    try:
        custom.patch_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=wf_name,
            body={'spec': {'shutdown': 'Stop'}},
        )
    except ApiException:
        pass
    try:
        custom.delete_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=wf_name,
        )
    except ApiException:
        pass


def _submit_via_config_processor(namespace, config):
    """Submit workflow using the config processor with --transformed-config.

    This bypasses the user→argo transformation (config is already denormalized)
    but still generates CRD resources with correct dependsOn, approval ConfigMaps,
    and concurrency ConfigMaps.
    """
    script_dir = os.environ.get('CONFIG_PROCESSOR_DIR', '/root/configProcessor')
    nodejs = os.environ.get('NODEJS', 'node')

    with tempfile.TemporaryDirectory() as temp_dir:
        # Write denormalized config to temp file
        config_path = os.path.join(temp_dir, 'transformed.json')
        with open(config_path, 'w') as f:
            json.dump(config, f)

        # Run config processor with --transformed-config (skips user→argo transform)
        output_dir = os.path.join(temp_dir, 'output')
        os.makedirs(output_dir)
        init_cmd = os.environ.get(
            'INITIALIZE_CMD',
            f"{nodejs} {script_dir}/index.js initialize"
        )
        subprocess.run(
            f"{init_cmd} --transformed-config {config_path} --output-dir {output_dir}",
            shell=True, check=True, capture_output=True, text=True
        )

        # Apply CRD resources (delete + apply, same as submission script)
        crd_path = os.path.join(output_dir, 'crdResources.yaml')
        if os.path.exists(crd_path):
            subprocess.run(
                ['kubectl', 'delete', '-f', crd_path, '--ignore-not-found',
                 '-n', namespace],
                check=False, capture_output=True
            )
            subprocess.run(
                ['kubectl', 'apply', '-f', crd_path, '-n', namespace],
                check=True, capture_output=True
            )

        # Apply approval + concurrency config maps
        for filename in ('approvalConfigMaps.yaml', 'concurrencyConfigMaps.yaml'):
            path = os.path.join(output_dir, filename)
            if os.path.exists(path):
                subprocess.run(
                    ['kubectl', 'apply', '-f', path, '-n', namespace],
                    check=False, capture_output=True
                )

        # Read the generated workflow config
        wf_config_path = os.path.join(output_dir, 'workflowMigration.config.yaml')
        with open(wf_config_path) as f:
            wf_config_str = f.read()

        # Submit workflow
        nonce = str(int(time.time()))
        workflow_yaml = f"""apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  name: {DEFAULT_WORKFLOW_NAME}
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: uniqueRunNonce
        value: "{nonce}"
      - name: approval-config
        value: "approval-config-0"
      - name: config
        value: |
{_indent(wf_config_str, 10)}"""

        subprocess.run(
            ['kubectl', 'create', '-f', '-', '-n', namespace],
            input=workflow_yaml, check=True, capture_output=True, text=True
        )
        return DEFAULT_WORKFLOW_NAME


def _indent(text, spaces):
    """Indent each line of text by the given number of spaces."""
    prefix = ' ' * spaces
    return '\n'.join(prefix + line for line in text.splitlines())


def _update_proxy_configs(proxies, target_names, enable):
    """Update noCapture on matching proxies. Returns True if any changed."""
    mode = "capture" if enable else "non-capture"
    want = not enable
    changed = False
    for proxy in proxies:
        if proxy['name'] not in target_names:
            continue
        pc = proxy.get('proxyConfig') or {}
        if pc.get('noCapture', False) == want:
            click.echo(f"  {proxy['name']}: already {mode}")
            continue
        pc['noCapture'] = want
        proxy['proxyConfig'] = pc
        click.echo(f"  {proxy['name']}: noCapture={want}")
        changed = True
    return changed


def _set_capture_mode_headless(namespace, proxy_names, enable):
    """Headless version for use by other commands (e.g., reset).

    Modifies the running workflow's proxy config and resubmits.
    Returns True on success.
    """
    try:
        wf_name, config = _get_workflow_config(namespace)
        if not config:
            click.echo("  ⚠ No running workflow found, skipping", err=True)
            return False

        proxies = config.get('proxies') or []
        if not _update_proxy_configs(proxies, set(proxy_names), enable):
            return True

        click.echo(f"  Stopping workflow '{wf_name}'...")
        _stop_and_delete_workflow(namespace, wf_name)

        click.echo("  Submitting workflow via config processor...")
        _submit_via_config_processor(namespace, config)
        click.echo("  ✓ Workflow resubmitted")
        return True

    except Exception as e:
        click.echo(f"  ⚠ Failed to disable capture: {e}", err=True)
        logger.exception(e)
        return False


def _set_capture_mode(ctx, name, namespace, enable):
    mode = "capture" if enable else "non-capture"
    try:
        load_k8s_config()

        wf_name, config = _get_workflow_config(namespace)
        if not config:
            click.echo("No running workflow found.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return

        proxies = config.get('proxies') or []
        proxy_names = [p['name'] for p in proxies]
        if name and name not in proxy_names:
            click.echo(f"Proxy '{name}' not found. Available: {', '.join(proxy_names)}", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return

        targets = {name} if name else set(proxy_names)
        if not _update_proxy_configs(proxies, targets, enable):
            return

        click.echo(f"Stopping workflow '{wf_name}'...")
        _stop_and_delete_workflow(namespace, wf_name)

        click.echo("Submitting workflow via config processor...")
        new_name = _submit_via_config_processor(namespace, config)
        click.echo(f"  ✓ Workflow '{new_name}' submitted ({mode} mode)")

    except subprocess.CalledProcessError as e:
        click.echo(f"Error: {e.stderr or e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
    except Exception as e:
        click.echo(f"Error: {e}", err=True)
        logger.exception(e)
        ctx.exit(ExitCode.FAILURE.value)


@click.group(name="proxy")
def proxy_group():
    """Manage capture proxy configuration."""


@proxy_group.command(name="disable-capture")
@click.argument('name', required=False, default=None)
@click.option('--namespace', default='ma')
@click.pass_context
def disable_capture(ctx, name, namespace):
    """Switch a proxy to non-capture (pass-through) mode.

    Reads the running workflow's denormalized config, sets noCapture=true,
    and resubmits through the config processor (with --transformed-config)
    to regenerate CRDs with updated dependsOn.

    After this, 'workflow reset <kafka>' is unblocked.
    The user's saved config is NOT modified.

    Example:
        workflow proxy disable-capture source-proxy
    """
    _set_capture_mode(ctx, name, namespace, enable=False)


@proxy_group.command(name="enable-capture")
@click.argument('name', required=False, default=None)
@click.option('--namespace', default='ma')
@click.pass_context
def enable_capture(ctx, name, namespace):
    """Re-enable traffic capture on a proxy.

    Reads the running workflow's config, sets noCapture=false, restores
    Kafka dependency on the CRD, and resubmits.

    Example:
        workflow proxy enable-capture source-proxy
    """
    _set_capture_mode(ctx, name, namespace, enable=True)
