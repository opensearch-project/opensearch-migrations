"""Tests for resource_tree_state_manager.py — incremental update and rebuild logic."""

import pytest
from textual.widgets import Tree

from console_link.workflow.resource_tree import (
    CONFIG_MODE_CURRENT_WORKFLOW,
    CONFIG_MODE_DEPLOYED,
    CONFIG_MODE_PENDING_SUBMIT,
    ResourceNode, ResourceGroup, ResourceSection,
)
from console_link.workflow.tui.resource_tree_state_manager import (
    ResourceTreeStateManager, RESOURCE_ID_PREFIX,
)


# --- Helpers ---

def make_resource(name, plural='snapshotmigrations', phase='Running', spec=None, status=None,
                  depends_on=None, workflow_progress=None):
    return ResourceNode(
        name=name, plural=plural, phase=phase,
        depends_on=depends_on or [], spec=spec or {}, status=status or {},
        workflow_progress=workflow_progress,
    )


def make_sections(resources_by_group=None):
    """Build minimal sections for testing. resources_by_group maps display_name → list of resources."""
    resources_by_group = resources_by_group or {}
    groups = []
    for display_name, resources in resources_by_group.items():
        plural = resources[0].plural if resources else 'snapshotmigrations'
        groups.append(ResourceGroup(plural=plural, display_name=display_name, resources=resources))
    return [ResourceSection(name='Test Section', groups=groups)]


def get_all_node_ids(root):
    """Collect all data['id'] values from the tree."""
    ids = []
    stack = list(root.children)
    while stack:
        node = stack.pop()
        if node.data and isinstance(node.data, dict) and 'id' in node.data:
            ids.append(node.data['id'])
        stack.extend(node.children)
    return ids


def find_node_by_id(root, target_id):
    """Find a node by its data ID."""
    stack = list(root.children)
    while stack:
        node = stack.pop()
        if node.data and isinstance(node.data, dict) and node.data.get('id') == target_id:
            return node
        stack.extend(node.children)
    return None


@pytest.fixture
def tree_and_manager():
    tree = Tree("root")
    mgr = ResourceTreeStateManager(tree_widget=tree, namespace="test")
    return tree, mgr


# --- Rebuild ---

class TestRebuild:
    def test_rebuild_populates_tree(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots', 'Completed')]})
        mgr.rebuild(sections)
        ids = get_all_node_ids(tree.root)
        assert 'section:Test Section' in ids
        assert 'group:Snapshot' in ids
        assert f'{RESOURCE_ID_PREFIX}snap-1' in ids

    def test_rebuild_clears_previous(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots')]})
        mgr.rebuild(sections)
        mgr.rebuild(make_sections({'Backfill': [make_resource('bf-1', 'snapshotmigrations')]}))
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}snap-1' not in ids
        assert f'{RESOURCE_ID_PREFIX}bf-1' in ids

    def test_rebuild_shows_spec_fields(self, tree_and_manager):
        tree, mgr = tree_and_manager
        resource = make_resource('proxy-1', 'captureproxies', spec={'podReplicas': 3, 'listenPort': 9201})
        mgr.rebuild(make_sections({'Capture': [resource]}))
        resource_node = find_node_by_id(tree.root, f'{RESOURCE_ID_PREFIX}proxy-1')
        labels = [str(c.label) for c in resource_node.children]
        assert any('podReplicas: 3' in ln for ln in labels)

    def test_rebuild_shows_live_status(self, tree_and_manager):
        tree, mgr = tree_and_manager
        resource = make_resource('bf-1', 'snapshotmigrations', status={
            'documentBackfill': {'summary': {'percentageCompleted': 50, 'shardsTotal': 10, 'shardsMigrated': 5}}
        })
        mgr.rebuild(make_sections({'Backfill': [resource]}))
        resource_node = find_node_by_id(tree.root, f'{RESOURCE_ID_PREFIX}bf-1')
        labels = [str(c.label) for c in resource_node.children]
        assert any('Backfill status:' in ln for ln in labels)

    def test_rebuild_shows_config_changes_and_mode(self, tree_and_manager):
        tree, mgr = tree_and_manager
        resource = make_resource('kafka-1', 'kafkaclusters', spec={'version': '3.6.0'})
        resource.config_diff = {
            'has_submitted_changes': True,
            'has_pending_submit_changes': True,
            'fields': [{
                'path': 'version',
                'label': 'version',
                'values': {
                    'deployed': {'present': True, 'value': '3.6.0'},
                    'submitted': {'present': True, 'value': '3.7.0'},
                    'pending': {'present': True, 'value': '3.8.0'},
                },
            }],
        }

        mgr.rebuild(make_sections({'Buffer': [resource]}))
        resource_node = find_node_by_id(tree.root, f'{RESOURCE_ID_PREFIX}kafka-1')
        assert 'to submit' in str(resource_node.label)
        labels = [str(c.label) for c in resource_node.children]
        assert any('deployed=3.6.0 | pending=3.7.0 | to-submit=3.8.0' in ln for ln in labels)

        mgr.set_config_value_mode('pendingSubmit')
        mgr.update(make_sections({'Buffer': [resource]}))
        resource_node = find_node_by_id(tree.root, f'{RESOURCE_ID_PREFIX}kafka-1')
        labels = [str(c.label) for c in resource_node.children]
        assert any('version: to-submit=3.8.0' in ln for ln in labels)

    def test_value_modes_filter_resources_by_projected_presence(self, tree_and_manager):
        tree, mgr = tree_and_manager
        delete_after_submit = make_resource('delete-after-submit', 'trafficreplays')
        delete_after_submit.config_presence = {'deployed': True, 'submitted': True, 'pending': False}
        create_after_submit = make_resource('create-after-submit', 'trafficreplays', 'Pending Config')
        create_after_submit.config_presence = {'deployed': False, 'submitted': False, 'pending': True}
        create_after_workflow = make_resource('create-after-workflow', 'trafficreplays', 'Pending Config')
        create_after_workflow.config_presence = {'deployed': False, 'submitted': True, 'pending': True}
        sections = make_sections({
            'Replay': [delete_after_submit, create_after_submit, create_after_workflow],
        })

        mgr.rebuild(sections)
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}delete-after-submit' in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-submit' in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-workflow' in ids

        mgr.set_config_value_mode(CONFIG_MODE_DEPLOYED)
        mgr.update(sections)
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}delete-after-submit' in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-submit' not in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-workflow' not in ids

        mgr.set_config_value_mode(CONFIG_MODE_CURRENT_WORKFLOW)
        mgr.update(sections)
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}delete-after-submit' in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-submit' not in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-workflow' in ids

        mgr.set_config_value_mode(CONFIG_MODE_PENDING_SUBMIT)
        mgr.update(sections)
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}delete-after-submit' not in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-submit' in ids
        assert f'{RESOURCE_ID_PREFIX}create-after-workflow' in ids


# --- Incremental Update ---

class TestIncrementalUpdate:
    def test_update_preserves_structure_when_no_change(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots')]})
        mgr.rebuild(sections)
        section_node = find_node_by_id(tree.root, 'section:Test Section')
        original_section_node = section_node
        mgr.update(sections)
        # Same node object should still be in tree (not replaced)
        assert find_node_by_id(tree.root, 'section:Test Section') is original_section_node

    def test_update_preserves_collapse_state(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots')]})
        mgr.rebuild(sections)
        # Collapse the group
        group_node = find_node_by_id(tree.root, 'group:Snapshot')
        group_node.collapse()
        assert not group_node.is_expanded
        # Update — same structure
        mgr.update(sections)
        group_node = find_node_by_id(tree.root, 'group:Snapshot')
        assert not group_node.is_expanded

    def test_update_detects_phase_change(self, tree_and_manager):
        tree, mgr = tree_and_manager
        resource = make_resource('snap-1', 'datasnapshots', 'Running')
        mgr.rebuild(make_sections({'Snapshot': [resource]}))
        # Change phase
        updated = make_resource('snap-1', 'datasnapshots', 'Completed')
        mgr.update(make_sections({'Snapshot': [updated]}))
        resource_node = find_node_by_id(tree.root, f'{RESOURCE_ID_PREFIX}snap-1')
        # Label should reflect new phase
        assert 'Completed' in str(resource_node.label)

    def test_update_handles_new_resource_added(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots')]})
        mgr.rebuild(sections)
        # Add a second resource
        updated = make_sections({'Snapshot': [
            make_resource('snap-1', 'datasnapshots'),
            make_resource('snap-2', 'datasnapshots'),
        ]})
        mgr.update(updated)
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}snap-2' in ids

    def test_update_handles_resource_removed(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = make_sections({'Snapshot': [
            make_resource('snap-1', 'datasnapshots'),
            make_resource('snap-2', 'datasnapshots'),
        ]})
        mgr.rebuild(sections)
        # Remove snap-2
        mgr.update(make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots')]}))
        ids = get_all_node_ids(tree.root)
        assert f'{RESOURCE_ID_PREFIX}snap-1' in ids
        assert f'{RESOURCE_ID_PREFIX}snap-2' not in ids

    def test_update_handles_new_group_added(self, tree_and_manager):
        tree, mgr = tree_and_manager
        mgr.rebuild(make_sections({'Snapshot': [make_resource('snap-1', 'datasnapshots')]}))
        # Now add a second group
        sections = [ResourceSection(name='Test Section', groups=[
            ResourceGroup(plural='datasnapshots', display_name='Snapshot',
                          resources=[make_resource('snap-1', 'datasnapshots')]),
            ResourceGroup(plural='snapshotmigrations', display_name='Backfill',
                          resources=[make_resource('bf-1', 'snapshotmigrations')]),
        ])]
        mgr.update(sections)
        ids = get_all_node_ids(tree.root)
        assert 'group:Backfill' in ids
        assert f'{RESOURCE_ID_PREFIX}bf-1' in ids

    def test_update_preserves_collapse_after_structural_change(self, tree_and_manager):
        tree, mgr = tree_and_manager
        sections = [ResourceSection(name='Test Section', groups=[
            ResourceGroup(plural='datasnapshots', display_name='Snapshot',
                          resources=[make_resource('snap-1', 'datasnapshots')]),
            ResourceGroup(plural='snapshotmigrations', display_name='Backfill',
                          resources=[make_resource('bf-1', 'snapshotmigrations')]),
        ])]
        mgr.rebuild(sections)
        # Collapse Backfill group
        backfill_node = find_node_by_id(tree.root, 'group:Backfill')
        backfill_node.collapse()
        # Add a third group (structural change at section level)
        sections[0].groups.append(
            ResourceGroup(plural='captureproxies', display_name='Capture',
                          resources=[make_resource('proxy-1', 'captureproxies')]))
        mgr.update(sections)
        # Backfill should still be collapsed
        backfill_node = find_node_by_id(tree.root, 'group:Backfill')
        assert not backfill_node.is_expanded

    def test_on_new_pod_called_for_pod_steps(self):
        tree = Tree("root")
        observed_pods = []
        mgr = ResourceTreeStateManager(tree_widget=tree, namespace="test",
                                       on_new_pod=lambda pod_id: observed_pods.append(pod_id))
        resource = make_resource('bf-1', 'snapshotmigrations', workflow_progress=[
            {'id': 'pod-1', 'display_name': 'step1', 'phase': 'Running', 'type': 'Pod',
             'started_at': '2026-01-01T10:00:00Z', 'children': []},
        ])
        mgr.rebuild(make_sections({'Backfill': [resource]}))
        assert 'pod-1' in observed_pods
