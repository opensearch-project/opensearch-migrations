"""Approve command for workflow CLI - approves pending gates via CRD status patching.

Three subcommands:
  step    — user-defined approval points in the migration config
  change  — runtime gates for Gated field changes (VAP denial type)
  retry   — runtime gates for Impossible field changes (require recovery first)

See PLAN_approve_semantics.md and PLAN_approve_implementation.md for design.
"""

import logging
import os

import click
from click.shell_completion import CompletionItem
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .argo_utils import DEFAULT_ARGO_SERVER_URL, get_workflow
from ..tree_utils import is_approval_node, get_node_input_parameter
from .crd_utils import (
    CRD_GROUP,
    CRD_VERSION,
    match_names,
)

logger = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────
# Label keys (must match MigrationInitializer.GATE_LABEL_* constants)
# ─────────────────────────────────────────────────────────────

LABEL_WORKFLOW = 'migrations.opensearch.org/workflow'
LABEL_RESOURCE_KIND = 'migrations.opensearch.org/resource-kind'
LABEL_RESOURCE_NAME = 'migrations.opensearch.org/resource-name'
LABEL_SOURCE = 'migrations.opensearch.org/source'
LABEL_TARGET = 'migrations.opensearch.org/target'
LABEL_SNAPSHOT = 'migrations.opensearch.org/snapshot'
LABEL_MIGRATION = 'migrations.opensearch.org/migration'


# ─────────────────────────────────────────────────────────────
# Data model
# ─────────────────────────────────────────────────────────────

class GateInfo:
    """Info about an approval gate used for display and pre-flight checks.

    Fields:
      name            — gate CRD name
      category        — 'step', 'change', or 'retry'
      status          — 'waiting' (workflow actively blocked) or
                        'pending' (gate exists but workflow not yet there)
      labels          — dict of labels from the gate CRD metadata
      reason          — VAP denial reason (for change/retry), or None
    """

    __slots__ = ('name', 'category', 'status', 'labels', 'reason')

    def __init__(self, name, category, status, labels=None, reason=None):
        self.name = name
        self.category = category
        self.status = status
        self.labels = labels or {}
        self.reason = reason

    @property
    def resource_kind(self):
        return self.labels.get(LABEL_RESOURCE_KIND)

    @property
    def resource_name(self):
        return self.labels.get(LABEL_RESOURCE_NAME)


# ─────────────────────────────────────────────────────────────
# Data fetchers
# ─────────────────────────────────────────────────────────────

def _classify_runtime_gate(denial_msg):
    """Return 'change' or 'retry' based on the VAP denial message.

    Messages containing 'Impossible' indicate a retry (recovery required).
    Anything else (including 'Gated' messages) is a change-confirmation.
    """
    if not denial_msg:
        return 'change'
    return 'retry' if 'Impossible' in denial_msg else 'change'


def _waiting_gates_from_workflow(namespace, workflow_name):
    """Fetch runtime gates (change + retry) from the Argo workflow.

    Returns a list of tuples (gate_name, denial_reason).
    """
    try:
        wf = get_workflow(namespace, workflow_name)
    except Exception as e:
        logger.debug(f"Could not fetch workflow {workflow_name}: {e}")
        return []
    if not wf:
        return []
    nodes = wf.get('status', {}).get('nodes', {})
    results = []
    for node in nodes.values():
        if node.get('phase') != 'Running':
            continue
        if not is_approval_node(node):
            continue
        gate_name = get_node_input_parameter(node, 'resourceName') or get_node_input_parameter(node, 'name')
        if not gate_name:
            continue
        # Find sibling tryApply's denial message via shared boundaryID
        reason = None
        boundary = node.get('boundaryID')
        if boundary:
            for sibling in nodes.values():
                if (sibling.get('boundaryID') == boundary and
                        sibling.get('phase') == 'Failed' and
                        sibling.get('message')):
                    msg = sibling['message']
                    for marker in ('denied request: ', 'message: '):
                        idx = msg.find(marker)
                        if idx >= 0:
                            reason = msg[idx + len(marker):].strip().rstrip('.')
                            break
                    if reason:
                        break
        results.append((gate_name, reason))
    return results


def _list_all_gates(namespace, workflow_name=None):
    """Fetch ApprovalGate CRDs in the namespace.

    If workflow_name is provided, use a label selector so only gates
    belonging to that workflow are returned. Gates without the workflow
    label (e.g. older ones) are excluded.

    Returns a list of (name, phase, labels_dict).
    """
    custom = client.CustomObjectsApi()
    kwargs = {
        'group': CRD_GROUP, 'version': CRD_VERSION,
        'namespace': namespace, 'plural': 'approvalgates',
    }
    if workflow_name:
        kwargs['label_selector'] = f"{LABEL_WORKFLOW}={workflow_name}"
    try:
        items = custom.list_namespaced_custom_object(**kwargs).get('items', [])
    except ApiException:
        return []
    results = []
    for item in items:
        md = item.get('metadata', {})
        results.append((
            md.get('name', ''),
            item.get('status', {}).get('phase', 'Unknown'),
            md.get('labels', {}) or {},
        ))
    return results


def _gate_category_from_name(name):
    """Classifier based on gate name suffix.

    Gate names ending in '.vapretry' are runtime (change or retry).
    Everything else is a step.
    """
    return 'runtime' if name.endswith('.vapretry') else 'step'


_VAPRETRY_SUFFIX = '.vapretry'


def _display_name(name_or_gate):
    """Human-friendly display name for a gate.

    Accepts either a plain name string or a GateInfo object.  When a
    GateInfo with resource-kind and resource-name labels is provided the
    display form is ``lowerkind.resource-name`` (e.g.
    ``capturedtraffic.capture-proxy-topic``).  Otherwise the
    ``.vapretry`` suffix is simply stripped.
    """
    if isinstance(name_or_gate, GateInfo):
        kind = name_or_gate.resource_kind
        rname = name_or_gate.resource_name
        if kind and rname and name_or_gate.category in ('change', 'retry'):
            return f"{kind.lower()}.{rname}"
        name = name_or_gate.name
    else:
        name = name_or_gate
    if name.endswith(_VAPRETRY_SUFFIX):
        return name[:-len(_VAPRETRY_SUFFIX)]
    return name


def _resolve_gate_name(user_input, available_names):
    """Given a user-supplied gate name (which may or may not include the
    .vapretry suffix) and the list of available gate names, return the
    matching fully-qualified name or None.
    """
    # Exact match on the full name
    if user_input in available_names:
        return user_input
    # Exact match after appending .vapretry
    suffixed = user_input + _VAPRETRY_SUFFIX
    if suffixed in available_names:
        return suffixed
    return None


# ─────────────────────────────────────────────────────────────
# Output formatting
# ─────────────────────────────────────────────────────────────


def _extract_fields_from_reason(reason):
    """Pull the bracketed field list out of a VAP denial message.

    Returns a string like 'noCapture, tls' or None if no brackets found.
    """
    if not reason:
        return None
    lbracket = reason.find('[')
    rbracket = reason.find(']', lbracket + 1) if lbracket >= 0 else -1
    if lbracket >= 0 and rbracket > lbracket:
        inside = reason[lbracket + 1:rbracket].strip()
        return inside or None
    return None


def _status_blurb(gate):
    """Short human description of why the gate is in its current state."""
    if gate.status == 'waiting' and gate.category == 'change':
        fields = _extract_fields_from_reason(gate.reason)
        return f"CHANGED: {fields}" if fields else "changed"
    if gate.status == 'waiting' and gate.category == 'retry':
        fields = _extract_fields_from_reason(gate.reason)
        return f"Requires reset. Incompatible fields: {fields}" if fields else "impossible change"
    if gate.status == 'waiting' and gate.category == 'step':
        return "WAITING FOR APPROVAL"
    if gate.status == 'pending' and gate.category == 'change':
        return "No change triggered"
    if gate.status == 'pending' and gate.category == 'step':
        return "Not yet reached"
    if gate.status == 'approved':
        return "Approved"
    return gate.status


def _format_gate_rows(gates, show_prereq=False, mark_ready=False):
    """Format a list of gates with status blurb after the name.

    Names containing '.' are aligned on the dot. The status blurb is
    shown in parentheses after the name.

    When mark_ready=True, gates with status 'waiting' get a leading '*'
    and are colored green.

    Returns a list of lines ready to echo.
    """
    if not gates:
        return []

    # Sort by display name for consistent ordering (matches shell tab-completion)
    gates = sorted(gates, key=lambda g: _display_name(g))

    # Compute name column width with dot alignment
    display_names = [_display_name(g) for g in gates]
    splits = [n.partition('.') for n in display_names]  # (left, '.', right)
    max_left = max(len(left) for left, _, _ in splits)
    max_right = max(len(right) for _, _, right in splits)
    has_any_dot = any(dot for _, dot, _ in splits)
    total_width = max_left + (1 if has_any_dot else 0) + max_right

    marker_width = 2 if mark_ready else 0
    lines = []
    for gate, (left, dot, right) in zip(gates, splits):
        # Format name with dot alignment
        aligned_left = left.rjust(max_left)
        if dot:
            aligned_right = right.ljust(max_right)
            aligned = f"{aligned_left}.{aligned_right}"
        else:
            aligned = aligned_left + ' ' * (total_width - max_left)

        is_ready = mark_ready and gate.status == 'waiting'
        marker = (click.style('* ', fg='green', bold=True)
                  if is_ready else
                  ' ' * marker_width)
        styled = click.style(aligned, fg='green') if is_ready else aligned

        blurb = _status_blurb(gate)
        suffix = f" ({blurb})" if blurb else ""
        lines.append(f"{marker}{styled}{suffix}")

        if show_prereq and gate.category == 'retry':
            prereq = _retry_prerequisite(gate)
            if prereq is not None:
                _, cmd = prereq
                indent = marker_width + total_width
                lines.append(f"{' ' * indent}  → run: {cmd}")
    return lines


def _status_from_phase(phase):
    return 'approved' if str(phase).lower() == 'approved' else 'pending'


def _gather_gates(namespace, workflow_name, category, pre_approve,
                  include_completed=False):
    """Return a list of GateInfo objects for the given category.

    - 'step': always lists the step gates the workflow is currently waiting
      on. With pre_approve=True, also lists non-runtime gates in the CRD
      that the workflow hasn't reached yet.
    - 'change': lists runtime gates whose denial message contains Gated.
      With pre_approve=True, also lists all vapretry gates in the CRD
      (since we can't tell change from retry without the workflow, we
      include them and let the user approve).
    - 'retry': lists runtime gates whose denial message contains
      Impossible. No pre_approve.

    By default completed gates are omitted so autocomplete and approval
    targets only include actionable gates. List views pass
    include_completed=True to show an audit view of the gate inventory.
    """
    all_gates = _list_all_gates(namespace, workflow_name)
    # name -> (phase, labels)
    gate_index = {n: (p, lbls) for n, p, lbls in all_gates}

    # Actively-waiting runtime gates from the workflow
    waiting = _waiting_gates_from_workflow(namespace, workflow_name)
    waiting_names = dict(waiting)

    results = []

    if category == 'step':
        # Step gates are non-vapretry gates
        for name, reason in waiting.items() if isinstance(waiting, dict) else waiting:
            pass  # iterate below via waiting_names
        for name, reason in waiting_names.items():
            if _gate_category_from_name(name) != 'step':
                continue
            phase, labels = gate_index.get(name, ('Unknown', {}))
            status = _status_from_phase(phase)
            if status == 'approved':
                if not include_completed:
                    continue
            else:
                status = 'waiting'
            results.append(GateInfo(
                name=name, category='step', status=status,
                labels=labels, reason=reason,
            ))
        if pre_approve:
            for name, phase, labels in all_gates:
                if _gate_category_from_name(name) != 'step':
                    continue
                if name in waiting_names:
                    continue
                if _status_from_phase(phase) == 'approved' and not include_completed:
                    continue
                results.append(GateInfo(
                    name=name, category='step', status=_status_from_phase(phase),
                    labels=labels, reason=None,
                ))

    elif category == 'change':
        for name, reason in waiting_names.items():
            if _gate_category_from_name(name) != 'runtime':
                continue
            if _classify_runtime_gate(reason) != 'change':
                continue
            phase, labels = gate_index.get(name, ('Unknown', {}))
            status = _status_from_phase(phase)
            if status == 'approved':
                if not include_completed:
                    continue
            else:
                status = 'waiting'
            results.append(GateInfo(
                name=name, category='change', status=status,
                labels=labels, reason=reason,
            ))
        if pre_approve:
            for name, phase, labels in all_gates:
                if _gate_category_from_name(name) != 'runtime':
                    continue
                if name in waiting_names:
                    continue
                if _status_from_phase(phase) == 'approved' and not include_completed:
                    continue
                results.append(GateInfo(
                    name=name, category='change', status=_status_from_phase(phase),
                    labels=labels, reason=None,
                ))

    elif category == 'retry':
        for name, reason in waiting_names.items():
            if _gate_category_from_name(name) != 'runtime':
                continue
            if _classify_runtime_gate(reason) != 'retry':
                continue
            phase, labels = gate_index.get(name, ('Unknown', {}))
            status = _status_from_phase(phase)
            if status == 'approved':
                if not include_completed:
                    continue
            else:
                status = 'waiting'
            results.append(GateInfo(
                name=name, category='retry', status=status,
                labels=labels, reason=reason,
            ))

    return results


# ─────────────────────────────────────────────────────────────
# Retry prerequisite checks
# ─────────────────────────────────────────────────────────────

_KIND_TO_PLURAL = {
    'KafkaCluster': 'kafkaclusters',
    'CapturedTraffic': 'capturedtraffics',
    'CaptureProxy': 'captureproxies',
    'DataSnapshot': 'datasnapshots',
    'SnapshotMigration': 'snapshotmigrations',
    'TrafficReplay': 'trafficreplays',
}


def _retry_prerequisite(gate):
    """Return (description, command) for the prereq required before approving
    this retry gate, or None if we can't determine one.
    """
    kind = gate.resource_kind
    name = gate.resource_name
    if not kind or not name:
        return None
    return (f"Reset the {kind} so the workflow can recreate it",
            f"workflow reset {name}")


def _resource_still_exists(namespace, kind, name):
    """Check if a migration CRD still exists."""
    plural = _KIND_TO_PLURAL.get(kind)
    if not plural:
        return False
    custom = client.CustomObjectsApi()
    try:
        custom.get_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural, name=name,
        )
        return True
    except ApiException as e:
        if e.status == 404:
            return False
        logger.warning(f"Unexpected error checking {kind}/{name}: {e}")
        return True  # err on the side of blocking


def _retry_blockers(namespace, gates):
    """Return a list of (gate, prereq) tuples for gates whose prereqs are
    still unsatisfied (resource still exists).
    """
    blockers = []
    for gate in gates:
        prereq = _retry_prerequisite(gate)
        if prereq is None:
            continue  # advisory-only prereqs aren't blockers
        kind = gate.resource_kind
        name = gate.resource_name
        if kind and name and _resource_still_exists(namespace, kind, name):
            blockers.append((gate, prereq))
    return blockers


# ─────────────────────────────────────────────────────────────
# Gate approval
# ─────────────────────────────────────────────────────────────

def approve_gate(namespace, name):
    """Patch an ApprovalGate's status.phase to Approved. Returns True on success."""
    custom = client.CustomObjectsApi()
    try:
        custom.patch_namespaced_custom_object_status(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural='approvalgates', name=name,
            body={'status': {'phase': 'Approved'}}
        )
        return True
    except ApiException as e:
        logger.error(f"Failed to approve {name}: {e}")
        return False


# ─────────────────────────────────────────────────────────────
# Output formatting
# ─────────────────────────────────────────────────────────────
# Shared option decorator and completers
# ─────────────────────────────────────────────────────────────

def _shared_options(func):
    """Apply options common to every approve subcommand."""
    func = click.option(
        '--token', hidden=True, envvar='ARGO_TOKEN',
        help='Bearer token for authentication'
    )(func)
    func = click.option(
        '--insecure', is_flag=True, default=True, hidden=True, envvar='WORKFLOW_INSECURE',
        help='Skip TLS certificate verification (default: True)'
    )(func)
    func = click.option(
        '--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE',
        help='Kubernetes namespace (default: ma)'
    )(func)
    func = click.option(
        '--argo-server',
        default=lambda: os.environ.get('ARGO_SERVER', DEFAULT_ARGO_SERVER_URL),
        hidden=True,
        help='Argo Server URL'
    )(func)
    func = click.option(
        '--workflow-name', default=DEFAULT_WORKFLOW_NAME,
        shell_complete=get_workflow_completions, hidden=True,
        help='Workflow name (default: migration-workflow)'
    )(func)
    return func


def _complete_names(category):
    """Build a shell_complete function for the given category."""

    def completer(ctx, _param, incomplete):
        namespace = ctx.params.get('namespace', 'ma')
        workflow_name = ctx.params.get('workflow_name') or DEFAULT_WORKFLOW_NAME
        pre_approve = ctx.params.get('pre_approve', False)
        selected_names = set(ctx.params.get('names') or ())
        try:
            load_k8s_config()
            gates = _gather_gates(namespace, workflow_name, category, pre_approve)
        except Exception:
            return []

        completions = []
        offered_names = set()
        # Offer display names (without the .vapretry suffix) for tab completion.
        for gate in gates:
            display_name = _display_name(gate)
            skip_gate = any((
                not display_name.startswith(incomplete),
                display_name in selected_names,
                gate.name in selected_names,
                display_name in offered_names,
            ))
            if skip_gate:
                continue
            completions.append(CompletionItem(display_name))
            offered_names.add(display_name)
        return completions

    return completer


# ─────────────────────────────────────────────────────────────
# Subcommand implementation (shared)
# ─────────────────────────────────────────────────────────────

def _require_single_action(ctx, names, list_flag, all_flag):
    """Enforce exactly one of: --list, --all, or at least one name."""
    n = int(bool(list_flag)) + int(bool(all_flag)) + int(bool(names))
    if n == 0:
        click.echo("Error: specify one of --list, --all, or one or more gate names.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(ExitCode.FAILURE.value)
    if n > 1:
        # Overlap is benign for --all + names, but we keep it strict.
        if list_flag and (all_flag or names):
            click.echo("Error: --list cannot be combined with --all or gate names.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        if all_flag and names:
            click.echo("Error: --all cannot be combined with explicit gate names.", err=True)
            ctx.exit(ExitCode.FAILURE.value)


def _run_list(gates, show_prereq=False):
    if not gates:
        click.echo("No gates available.")
        return
    for line in _format_gate_rows(gates, show_prereq=show_prereq):
        click.echo(line)


def _list_all_categories(namespace, workflow_name):
    """Print gates across all three categories, grouped by category.

    Within each section, ready-to-approve gates come first (with a '*'
    marker and green name), followed by gates that can be pre-approved.
    """
    printed_any = False
    for category in ('step', 'change', 'retry'):
        pre = (category != 'retry')
        gates = _gather_gates(
            namespace, workflow_name, category,
            pre_approve=pre, include_completed=True,
        )
        if not gates:
            continue
        # Ready gates first, then pending, preserving order within each group.
        ready = [g for g in gates if g.status == 'waiting']
        pending = [g for g in gates if g.status != 'waiting']
        ordered = ready + pending

        if printed_any:
            click.echo()
        click.echo(click.style(f"{category}:", bold=True))
        for line in _format_gate_rows(
            ordered,
            show_prereq=(category == 'retry'),
            mark_ready=True,
        ):
            click.echo(f"  {line}")
        printed_any = True

    if not printed_any:
        click.echo("No gates available.")


def _matches_approval_category(gate_name, category):
    gate_category = _gate_category_from_name(gate_name)
    if category in ('change', 'retry'):
        return gate_category == 'runtime'
    return gate_category == category


def _find_already_approved(namespace, workflow_name, names, category):
    """Return display names of gates matching *names* that are already approved."""
    try:
        all_gates = _list_all_gates(namespace, workflow_name)
    except Exception:
        return []
    cat = category or 'step'
    already = []
    for pattern in names:
        for gname, phase, labels in all_gates:
            if not _matches_approval_category(gname, cat):
                continue
            if _status_from_phase(phase) != 'approved':
                continue
            disp = _display_name(gname)
            if gname == pattern or disp == pattern or any(match_names([disp], pattern)):
                if disp not in already:
                    already.append(disp)
    return already


def _resolve_targets(ctx, gates, names, all_flag, namespace=None,
                     workflow_name=None, category=None):
    """Resolve the set of gates to approve from --all or a list of names.

    User-supplied names can be either the full gate name (with .vapretry
    suffix) or the display form (without suffix); both are accepted.
    Glob patterns match against display names for user friendliness.

    Returns a list of GateInfo. Exits on error.
    """
    if all_flag:
        return list(gates)

    # Map from both forms (display and full) to the GateInfo.
    display_to_gate = {_display_name(g): g for g in gates}
    full_to_gate = {g.name: g for g in gates}
    available_display_names = list(display_to_gate.keys())

    matched = []
    for pattern in names:
        # Try full exact match first
        if pattern in full_to_gate:
            gate = full_to_gate[pattern]
            if gate not in matched:
                matched.append(gate)
            continue
        # Then match on display names (supports both exact and globs)
        for disp in match_names(available_display_names, pattern):
            gate = display_to_gate[disp]
            if gate not in matched:
                matched.append(gate)

    if not matched:
        # Check if the user's targets are already approved
        if namespace and names:
            already = _find_already_approved(namespace, workflow_name, names, category)
            if already:
                for disp in already:
                    click.echo(f"  Already approved: {disp}")
                return []

        click.echo(f"No gates match {list(names)}.")
        if gates:
            click.echo("Available gates:")
            for line in _format_gate_rows(gates):
                click.echo(f"  {line}")
        ctx.exit(ExitCode.FAILURE.value)
    return matched


def _apply_approvals(ctx, namespace, targets):
    for gate in targets:
        display = _display_name(gate)
        if approve_gate(namespace, gate.name):
            click.echo(f"  ✓ Approved {display}")
        else:
            click.echo(f"  ✗ Failed to approve {display}", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return
    click.echo(f"\nApproved {len(targets)} gate(s).")


def _run_subcommand(ctx, category, names, list_flag, all_flag, pre_approve,
                    workflow_name, namespace, enforce_retry_prereqs=False):
    """Core logic shared by all three subcommands."""
    _require_single_action(ctx, names, list_flag, all_flag)

    try:
        load_k8s_config()
    except Exception as e:
        click.echo(f"Error: could not load Kubernetes configuration: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    gates = _gather_gates(
        namespace, workflow_name, category,
        pre_approve=(pre_approve or list_flag),
        include_completed=list_flag,
    )

    if list_flag:
        _run_list(gates, show_prereq=(category == 'retry'))
        return

    if not gates:
        # Before reporting failure, check if the user's targets are already approved
        if names and not all_flag:
            already = _find_already_approved(namespace, workflow_name, names, category)
            if already:
                for disp in already:
                    click.echo(f"  Already approved: {disp}")
                return

        if category == 'step':
            msg = ("No pending steps found."
                   if pre_approve else
                   "No steps are currently being waited on by the workflow.")
        elif category == 'change':
            msg = ("No pending change gates found."
                   if pre_approve else
                   "No gated changes are currently being waited on.")
        else:
            msg = "No retry gates are currently being waited on."
        click.echo(msg)
        ctx.exit(ExitCode.FAILURE.value)
        return

    targets = _resolve_targets(ctx, gates, names, all_flag, namespace, workflow_name, category)

    if not targets:
        return

    if enforce_retry_prereqs:
        blockers = _retry_blockers(namespace, targets)
        if blockers:
            click.echo("Cannot approve retry gates. These resources still exist:", err=True)
            for gate, _ in blockers:
                click.echo(f"  {gate.resource_kind}/{gate.resource_name}", err=True)
            click.echo("\nRun the following, then re-run the approve command:", err=True)
            shown = set()
            for _, (_, cmd) in blockers:
                if cmd not in shown:
                    click.echo(f"  {cmd}", err=True)
                    shown.add(cmd)
            ctx.exit(ExitCode.FAILURE.value)
            return

    _apply_approvals(ctx, namespace, targets)


# ─────────────────────────────────────────────────────────────
# Click group + subcommands
# ─────────────────────────────────────────────────────────────

class _OrderedGroup(click.Group):
    """Group that lists its subcommands in registration order."""

    def list_commands(self, ctx):
        return [name for name, command in self.commands.items() if not command.hidden]


@click.group(name="approve", invoke_without_command=True, cls=_OrderedGroup)
@click.option('--list', 'list_flag', is_flag=True, default=False,
              help='List gates across all approval categories')
@_shared_options
@click.pass_context
def approve_group(ctx, list_flag, workflow_name, argo_server, namespace, insecure, token):
    """Approve workflow gates.

    Run any subcommand with --help for details.
    """
    if ctx.invoked_subcommand is not None:
        return
    if not list_flag:
        click.echo(ctx.get_help())
        ctx.exit(ExitCode.FAILURE.value)
        return
    try:
        load_k8s_config()
    except Exception as e:
        click.echo(f"Error: could not load Kubernetes configuration: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return
    _list_all_categories(namespace, workflow_name)


@approve_group.command("step")
@click.argument('names', nargs=-1, required=False,
                shell_complete=_complete_names('step'))
@click.option('--list', 'list_flag', is_flag=True, default=False,
              help='List available step gates and exit')
@click.option('--all', 'all_flag', is_flag=True, default=False,
              help='Approve every matching step gate')
@click.option('--pre-approve', is_flag=True, default=False,
              help='Include step gates the workflow has not reached yet')
@_shared_options
@click.pass_context
def approve_step(ctx, names, list_flag, all_flag, pre_approve,
                 workflow_name, argo_server, namespace, insecure, token):
    """Approve user-defined migration checkpoints.

    \b
    Examples:
        workflow approve step --list
        workflow approve step evaluatemetadata.source-target-snap1-migration-0
        workflow approve step --pre-approve --all
    """
    _run_subcommand(ctx, 'step', names, list_flag, all_flag, pre_approve,
                    workflow_name, namespace)


@approve_group.command("change")
@click.argument('names', nargs=-1, required=False,
                shell_complete=_complete_names('change'))
@click.option('--list', 'list_flag', is_flag=True, default=False,
              help='List change gates the workflow is stuck on')
@click.option('--all', 'all_flag', is_flag=True, default=False,
              help='Approve every matching change gate')
@click.option('--pre-approve', is_flag=True, default=False,
              help='Include change gates whose resource has not been updated yet')
@_shared_options
@click.pass_context
def approve_change(ctx, names, list_flag, all_flag, pre_approve,
                   workflow_name, argo_server, namespace, insecure, token):
    """Acknowledge gated configuration field changes.

    The VAP denied an UPDATE to a resource because a gated field
    (e.g. noCapture, tls) changed. Approving lets the workflow retry.

    \b
    Examples:
        workflow approve change --list
        workflow approve change captureproxy.capture-proxy
        workflow approve change --pre-approve --all
    """
    _run_subcommand(ctx, 'change', names, list_flag, all_flag, pre_approve,
                    workflow_name, namespace)


@approve_group.command("retry")
@click.argument('names', nargs=-1, required=False,
                shell_complete=_complete_names('retry'))
@click.option('--list', 'list_flag', is_flag=True, default=False,
              help='List retry gates the workflow is stuck on')
@click.option('--all', 'all_flag', is_flag=True, default=False,
              help='Approve every matching retry gate (blocked if prereqs unmet)')
@_shared_options
@click.pass_context
def approve_retry(ctx, names, list_flag, all_flag,
                  workflow_name, argo_server, namespace, insecure, token):
    """Confirm recovery is complete after an impossible change.

    The VAP denied an UPDATE because the field is immutable in place.
    The user must reset the resource first; approving tells the workflow
    the recovery is done so it can re-create the resource fresh.

    \b
    Examples:
        workflow approve retry --list
        workflow approve retry captureproxy.capture-proxy
        workflow approve retry --all
    """
    _run_subcommand(ctx, 'retry', names, list_flag, all_flag, False,
                    workflow_name, namespace, enforce_retry_prereqs=True)
