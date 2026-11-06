"""Change-restriction override for running migration resources: a break-glass tool.

A maintainer/support escape hatch, deliberately left out of help text, completion, denial messages, and hints.
It annotates a resource so the admission policies accept a change they would otherwise refuse, for the current
workflow run only, then approves the gate the workflow is parked on so its retry loop re-applies the resource.
See docs/changeRestrictionOverrideRunbook.md.

The annotation keys and the run-number comparison must match the generated ValidatingAdmissionPolicies
(orchestrationSpecs/packages/schemas/src/generateMigrationResources.ts).
"""

import getpass
import logging
import re
from datetime import datetime, timezone

import click
from kubernetes import client, config
from kubernetes.client.rest import ApiException

from .argo_utils import get_workflow
from .crd_utils import CRD_GROUP, CRD_VERSION

logger = logging.getLogger(__name__)

FIELD_ANNOTATION_PREFIX = 'change-restriction-override.migrations.opensearch.org/'
ALL_FIELDS_ANNOTATION = 'migrations.opensearch.org/change-restriction-override-all-fields'
RUN_NUMBER_PARAMETER = 'migrationRunNumber'
_ANNOTATION_NAME_PART = re.compile(r'^[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?$')

_IMPOSSIBLE_FIELD = re.compile(r'Impossible: ([\w.]+) cannot')
_GATED_FIELDS = re.compile(r'fields: (.+?)\. Approve')

WARNING = """\
OVERRIDING CHANGE RESTRICTIONS: this applies a change the admission policies would refuse,
to a resource that is still running.
  - The change is applied in place and may only partly take effect.
  - Documents and metadata already migrated are not reprocessed.
  - The downstream Kubernetes or Strimzi apply may still reject the change; resetting the resource is then the
    way out.
  - The override lasts for this workflow run only and stays on the resource as an audit record."""


class ChangeRestrictionOverrideError(Exception):
    """A override request that can't proceed; the message says why."""


def fields_from_denial(reason):
    """The spec fields a VAP denial message names, in order, without duplicates."""
    if not reason:
        return []
    fields = _IMPOSSIBLE_FIELD.findall(reason)
    for listed in _GATED_FIELDS.findall(reason):
        for item in listed.split(','):
            name = item.strip()
            if name.endswith(' (increase)'):
                name = name[:-len(' (increase)')]
            if name:
                fields.append(name)
    return list(dict.fromkeys(fields))


def annotation_for_field(field):
    if len(field) > 63 or not _ANNOTATION_NAME_PART.match(field):
        raise ChangeRestrictionOverrideError(f"'{field}' is not a spec field name that can be used in an annotation")
    return FIELD_ANNOTATION_PREFIX + field


def current_run_number(namespace, workflow_name):
    """The run number the workflow stamps on resources it applies (its migrationRunNumber parameter)."""
    workflow = get_workflow(namespace, workflow_name)
    if not workflow:
        raise ChangeRestrictionOverrideError(f"Workflow {workflow_name} not found in namespace {namespace}")
    for parameter in workflow.get('spec', {}).get('arguments', {}).get('parameters', []) or []:
        if parameter.get('name') == RUN_NUMBER_PARAMETER and str(parameter.get('value', '')).strip():
            return str(parameter['value']).strip()
    raise ChangeRestrictionOverrideError(f"Workflow {workflow_name} has no {RUN_NUMBER_PARAMETER} parameter")


def _get_resource(namespace, plural, name):
    try:
        return client.CustomObjectsApi().get_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION, namespace=namespace, plural=plural, name=name)
    except ApiException as e:
        if e.status == 404:
            raise ChangeRestrictionOverrideError(f"{plural}/{name} not found; nothing to override") from e
        raise


def check_resource_can_be_overridden(resource, kind, name):
    """Refuse resources the lifecycle guards seal: Completed, or being deleted."""
    phase = (resource.get('status') or {}).get('phase')
    if phase == 'Completed':
        raise ChangeRestrictionOverrideError(
            f"{kind}/{name} is Completed and its spec is sealed. Use 'workflow reset {name}' instead.")
    if phase == 'Deleting' or (resource.get('metadata') or {}).get('deletionTimestamp'):
        raise ChangeRestrictionOverrideError(f"{kind}/{name} is being deleted. Use 'workflow reset {name}' instead.")


def _current_user():
    try:
        _, context = config.list_kube_config_contexts()
        user = (context or {}).get('context', {}).get('user')
        if user:
            return user
    except Exception:
        pass
    try:
        return getpass.getuser()
    except Exception:
        return 'unknown'


def plan(gate, requested_fields, all_fields):
    """Return (annotation keys, description of what is overridden) for a gate."""
    if all_fields:
        return [ALL_FIELDS_ANNOTATION], 'all fields'
    fields = list(requested_fields) or fields_from_denial(gate.reason)
    if not fields:
        raise ChangeRestrictionOverrideError(
            f"Could not tell which fields {gate.resource_kind}/{gate.resource_name} was denied for; "
            "name them with --field, or use --all-fields")
    return [annotation_for_field(field) for field in fields], ', '.join(fields)


def override_change_restrictions(namespace, workflow_name, gate, plural, requested_fields, all_fields, assume_yes):
    """Annotate the gate's resource for this run and approve the gate. Returns the approval message."""
    kind, name = gate.resource_kind, gate.resource_name
    if not kind or not name or not plural:
        raise ChangeRestrictionOverrideError(f"Gate {gate.name} doesn't identify the resource it guards")
    resource = _get_resource(namespace, plural, name)
    check_resource_can_be_overridden(resource, kind, name)
    annotations, description = plan(gate, requested_fields, all_fields)
    run_number = current_run_number(namespace, workflow_name)

    click.echo(click.style(WARNING, fg='yellow'), err=True)
    click.echo(f"\nResource: {kind}/{name}\nOverride: {description}\nRun:      {run_number}\n", err=True)
    if not assume_yes:
        typed = click.prompt(f"Type the resource name ({name}) to continue", default='', show_default=False,
                             err=True)
        if typed != name:
            raise ChangeRestrictionOverrideError("Confirmation did not match; nothing was changed")

    client.CustomObjectsApi().patch_namespaced_custom_object(
        group=CRD_GROUP, version=CRD_VERSION, namespace=namespace, plural=plural, name=name,
        body={'metadata': {'annotations': {key: run_number for key in annotations}}})

    user = _current_user()
    stamp = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    message = f"change-restriction override: {description} by {user} at {stamp}"
    logger.warning("%s on %s/%s for run %s (%s)", message, kind, name, run_number, ', '.join(annotations))
    client.CustomObjectsApi().patch_namespaced_custom_object_status(
        group=CRD_GROUP, version=CRD_VERSION, namespace=namespace, plural='approvalgates', name=gate.name,
        body={'status': {'phase': 'Approved', 'message': message}})
    return message
