"""Contract between the workflow templates and the progress overlays on wait steps.

`workflow status` and `workflow manage` attach SnapshotMigration.status.documentBackfill and
DataSnapshot.status.snapshotCreation to the steps that wait on those resources. Which steps those
are is decided in orchestrationSpecs, so this test doesn't name any of them. It reads the rendered
WorkflowTemplates that orchestrationSpecs' outputMatch test keeps identical to the TypeScript
source, finds every step whose template waits on one of those kinds (a `get` of that kind with a
successCondition), and renders it through both commands. Renaming a step or template, or adding
a caller, changes those snapshots and is exercised here without editing this file.

Nodes are shaped the way Argo reports them (checked against a live workflow): a Retry node named
for the step with one Pod per attempt, all carrying the step's templateRef and inputs, or a bare
Skipped node with a templateRef and no inputs.
"""

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

import pytest
from textual.widgets import Tree

from console_link.workflow import tree_utils
from console_link.workflow.commands.status import StatusCommandHandler, WorkflowDataFetcher
from console_link.workflow.services.workflow_service import WorkflowService
from console_link.workflow.tui.tree_state_manager import TreeStateManager

SNAPSHOT_DIR = (Path(__file__).resolve().parents[5] /
                'orchestrationSpecs/packages/migration-workflow-templates/tests/__snapshots__')
NAMESPACE = 'ma'
RESOURCE_NAME = 'the-resource'

# What each overlaid kind's CR reports, and the text both commands must show for it.
CR_STATUS = {
    'SnapshotMigration': {'status': {'documentBackfill': {
        'phase': 'Running', 'updatedAt': '2026-05-14T10:05:00Z',
        'summary': {'percentageCompleted': 50.0, 'shardsTotal': 4, 'shardsMigrated': 2,
                    'shardsInProgress': 1, 'shardsWaiting': 1}}}},
    'DataSnapshot': {'status': {'snapshotCreation': {
        'phase': 'Running', 'updatedAt': '2026-05-14T10:05:00Z',
        'summary': {'shardsTotal': 4, 'shardsSuccessful': 2, 'shardsFailed': 0,
                    'dataProcessed': '12.3', 'dataProcessedUnit': 'mb', 'eta': '0h 1m 0s'}}}},
}
EXPECTED_TEXT = {
    'SnapshotMigration': ['RFS: Running (50%, shards 2/4, in progress 1, waiting 1)',
                          'updated 2026-05-14T10:05:00Z'],
    'DataSnapshot': ['Snapshot: Running (shards 2/4', 'failed 0, data 12.3 mb, ETA 0h 1m 0s)',
                     'updated 2026-05-14T10:05:00Z'],
}
OVERLAY_PREFIXES = ('RFS:', 'Snapshot:')


@dataclass(frozen=True)
class StepCall:
    """One step in a rendered WorkflowTemplate and the template it invokes."""
    workflow_template: str
    parent_template: str
    step_name: str
    target: tuple  # (WorkflowTemplate name, template name)
    via_template_ref: bool
    argument_names: frozenset

    @property
    def id(self) -> str:
        return f'{self.workflow_template}/{self.parent_template}/{self.step_name}'


_KIND_LINE = re.compile(r'^kind:\s*(\S+)\s*$', re.MULTILINE)


def _waited_kind(template: Dict[str, Any]) -> Optional[str]:
    """The resource kind a template blocks on, judged by what it does rather than its name."""
    resource = template.get('resource') or {}
    if resource.get('action') != 'get' or not resource.get('successCondition'):
        return None
    match = _KIND_LINE.search(resource.get('manifest', ''))
    return match.group(1) if match else None


def _load_snapshots() -> List[Dict[str, Any]]:
    paths = sorted(SNAPSHOT_DIR.glob('*.snap.json'))
    assert paths, f'No rendered WorkflowTemplates found under {SNAPSHOT_DIR}'
    return [json.loads(p.read_text()) for p in paths]


def _discover():
    """Return ({(wft, template): waited kind}, [StepCall]) across every rendered WorkflowTemplate."""
    snapshots = _load_snapshots()
    waited = {}
    calls = []
    for wft in snapshots:
        wft_name = wft['metadata']['name']
        for template in wft['spec']['templates']:
            kind = _waited_kind(template)
            if kind:
                waited[(wft_name, template['name'])] = kind
            steps = [s for group in template.get('steps') or [] for s in group]
            steps += (template.get('dag') or {}).get('tasks') or []
            for step in steps:
                ref = step.get('templateRef')
                target = (ref['name'], ref['template']) if ref else (wft_name, step.get('template'))
                args = frozenset(p['name'] for p in (step.get('arguments') or {}).get('parameters') or [])
                calls.append(StepCall(wft_name, template['name'], step['name'], target, bool(ref), args))
    return waited, calls


WAITED_KINDS, STEP_CALLS = _discover()
WAIT_CALLS = [c for c in STEP_CALLS if WAITED_KINDS.get(c.target) in CR_STATUS]
# Steps that pass a resourceName but don't wait on an overlaid kind (reads, patches, other waits).
NON_WAIT_CALLS = [c for c in STEP_CALLS
                  if 'resourceName' in c.argument_names and WAITED_KINDS.get(c.target) not in CR_STATUS]


def test_every_overlaid_kind_has_a_waiting_step():
    """Guards the discovery itself: if nothing is found, the parametrized tests would pass vacuously."""
    found = {WAITED_KINDS[c.target] for c in WAIT_CALLS}
    assert found == set(CR_STATUS), f'Waiting steps found only for {found}'
    assert NON_WAIT_CALLS, 'No non-waiting steps that pass resourceName were found'


def _ref_fields(call: StepCall) -> Dict[str, Any]:
    if call.via_template_ref:
        return {'templateRef': {'name': call.target[0], 'template': call.target[1]}}
    return {'templateName': call.target[1]}


def _inputs() -> Dict[str, Any]:
    return {'parameters': [{'name': 'resourceName', 'value': RESOURCE_NAME}]}


def _step_nodes(call: StepCall, attempt_phases: List[str]) -> Dict[str, Dict[str, Any]]:
    """A Retry node for the step with one Pod per attempt, as Argo records a retried template."""
    ref = _ref_fields(call)
    attempts = {
        f'step-{i}': {'id': f'step-{i}', 'displayName': f'{call.step_name}({i})', 'type': 'Pod',
                      'phase': phase, 'boundaryID': 'root', 'inputs': _inputs(),
                      'startedAt': f'2026-05-14T10:0{i}:00Z', **ref}
        for i, phase in enumerate(attempt_phases)
    }
    retry = {'id': 'step', 'displayName': call.step_name, 'type': 'Retry', 'phase': attempt_phases[-1],
             'boundaryID': 'root', 'inputs': _inputs(), 'children': list(attempts), **ref}
    return {'step': retry, **attempts}


def _skipped_nodes(call: StepCall) -> Dict[str, Dict[str, Any]]:
    return {'step': {'id': 'step', 'displayName': call.step_name, 'type': 'Skipped', 'phase': 'Skipped',
                     'boundaryID': 'root', **_ref_fields(call)}}


def _workflow(step_nodes: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    root = {'id': 'root', 'displayName': 'root', 'type': 'Steps', 'phase': 'Running', 'children': ['step']}
    return {'metadata': {'name': 'wf'},
            'status': {'phase': 'Running', 'startedAt': '2026-05-14T10:00:00Z',
                       'nodes': {'root': root, **step_nodes}}}


class _Fetcher(WorkflowDataFetcher):
    def __init__(self, workflow: Dict[str, Any]):
        super().__init__(WorkflowService())
        self.workflow = workflow

    def get_workflow_data(self, workflow_name, argo_server, namespace, insecure):
        return self.workflow


def _render_status(workflow: Dict[str, Any], capsys) -> str:
    handler = StatusCommandHandler(WorkflowService())
    handler.data_fetcher = _Fetcher(workflow)
    handler.handle_status_command('wf', 'https://argo', NAMESPACE, False, False, False)
    return ' '.join(capsys.readouterr().out.split())


def _render_manage_labels(workflow: Dict[str, Any]) -> str:
    manager = TreeStateManager(tree_widget=Tree('root'), namespace=NAMESPACE)
    manager.rebuild(workflow)
    labels = []
    pending = list(manager.tree.root.children)
    while pending:
        node = pending.pop()
        labels.append(node.label.plain)
        pending.extend(node.children)
    return ' '.join(' '.join(labels).split())


@pytest.fixture
def cr_reads(monkeypatch):
    """Serve CR status by kind and record every read; keep the ApprovalGate overlay off the cluster."""
    reads = []

    def reader(kind):
        def read(name, namespace):
            reads.append((kind, name, namespace))
            return CR_STATUS[kind]
        return read

    monkeypatch.setattr(tree_utils, '_default_snapshot_migration_reader', reader('SnapshotMigration'))
    monkeypatch.setattr(tree_utils, '_default_data_snapshot_reader', reader('DataSnapshot'))
    monkeypatch.setattr(tree_utils, '_fetch_approval_gate_phases', lambda namespace, names: {})
    return reads


@pytest.mark.parametrize('attempt_phases', [['Running'], ['Failed', 'Running']], ids=['first-attempt', 'retried'])
@pytest.mark.parametrize('call', WAIT_CALLS, ids=lambda c: c.id)
@pytest.mark.parametrize('render', ['status', 'manage'])
def test_waiting_step_shows_cr_progress(call, attempt_phases, render, cr_reads, capsys):
    kind = WAITED_KINDS[call.target]
    workflow = _workflow(_step_nodes(call, attempt_phases))
    output = _render_status(workflow, capsys) if render == 'status' else _render_manage_labels(workflow)

    assert (kind, RESOURCE_NAME, NAMESPACE) in cr_reads
    for expected in EXPECTED_TEXT[kind]:
        assert expected in output, f'{call.id} ({render}): missing {expected!r} in {output!r}'


@pytest.mark.parametrize('call', WAIT_CALLS, ids=lambda c: c.id)
def test_skipped_waiting_step_reads_nothing(call, cr_reads, capsys):
    output = _render_status(_workflow(_skipped_nodes(call)), capsys)

    assert cr_reads == []
    assert '(Skipped)' in output
    assert not any(prefix in output for prefix in OVERLAY_PREFIXES)


@pytest.mark.parametrize('call', NON_WAIT_CALLS, ids=lambda c: c.id)
def test_non_waiting_step_naming_a_resource_gets_no_overlay(call, cr_reads, capsys):
    output = _render_status(_workflow(_step_nodes(call, ['Running'])), capsys)

    assert cr_reads == []
    assert not any(prefix in output for prefix in OVERLAY_PREFIXES)
