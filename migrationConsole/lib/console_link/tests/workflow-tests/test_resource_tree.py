"""Tests for resource_tree.py — resource-centric tree building and formatting."""


from console_link.workflow.resource_tree import (
    CONFIG_MODE_CURRENT_WORKFLOW,
    CONFIG_MODE_DEPLOYED,
    CONFIG_MODE_PENDING_SUBMIT,
    ResourceGroup,
    ResourceNode,
    ResourceSection,
    _build_tree_from_raw, _nest_topics_under_kafka,
    apply_config_overlays,
    format_config_diff_fields,
    format_resource_diagnostics,
    resource_visible_in_config_mode,
    format_spec_fields, format_live_status, maybe_rewrite_wait_step,
    has_notable_steps, collect_notable_steps, find_last_succeeded,
    mark_not_configured_groups,
)


# --- Helpers ---

def make_cr(plural, name, phase='Ready', spec=None, status=None, depends_on=None):
    """Create a minimal CR dict as returned by list_migration_resources_full."""
    return {
        'metadata': {'name': name, 'creationTimestamp': '2026-01-01T00:00:00Z'},
        'spec': {**(spec or {}), 'dependsOn': depends_on or []},
        'status': {'phase': phase, **(status or {})},
    }


def make_resource(plural='snapshotmigrations', name='test', phase='Running',
                  spec=None, status=None, depends_on=None):
    return ResourceNode(
        name=name, plural=plural, phase=phase,
        depends_on=depends_on or [], spec=spec or {}, status=status or {},
    )


# --- _build_tree_from_raw ---

class TestBuildTreeFromRaw:
    def test_empty_input_returns_empty_sections(self):
        sections = _build_tree_from_raw({})
        assert len(sections) == 2  # Snapshot Migration, Live Traffic Migration
        for s in sections:
            for g in s.groups:
                assert g.resources == []

    def test_snapshot_placed_in_correct_section(self):
        raw = {'datasnapshots': [make_cr('datasnapshots', 'my-snap', 'Completed')]}
        sections = _build_tree_from_raw(raw)
        snapshot_section = sections[0]
        assert snapshot_section.name == 'Snapshot Migration'
        snap_group = snapshot_section.groups[0]
        assert snap_group.display_name == 'Snapshot'
        assert len(snap_group.resources) == 1
        assert snap_group.resources[0].name == 'my-snap'
        assert snap_group.resources[0].phase == 'Completed'

    def test_multiple_resources_in_same_group(self):
        raw = {'trafficreplays': [
            make_cr('trafficreplays', 'replay-a', 'Running'),
            make_cr('trafficreplays', 'replay-b', 'Pending'),
        ]}
        sections = _build_tree_from_raw(raw)
        replay_group = sections[1].groups[2]  # Live Traffic → Replay
        assert len(replay_group.resources) == 2

    def test_depends_on_parsed(self):
        raw = {'snapshotmigrations': [
            make_cr('snapshotmigrations', 'backfill', 'Pending',
                    depends_on=['my-snap'])
        ]}
        sections = _build_tree_from_raw(raw)
        resource = sections[0].groups[1].resources[0]
        assert resource.depends_on == ['my-snap']

    def test_missing_status_defaults_to_unknown(self):
        raw = {'datasnapshots': [{
            'metadata': {'name': 'no-status', 'creationTimestamp': None},
            'spec': {},
        }]}
        sections = _build_tree_from_raw(raw)
        resource = sections[0].groups[0].resources[0]
        assert resource.phase == 'Unknown'


# --- _nest_topics_under_kafka ---

class TestNestTopicsUnderKafka:
    def test_topic_nested_under_parent_kafka(self):
        kafka = ResourceNode(name='kafka-1', plural='kafkaclusters', phase='Ready',
                             depends_on=[], spec={}, status={})
        topic = ResourceNode(name='topic-1', plural='capturedtraffics', phase='Ready',
                             depends_on=[], spec={'kafkaClusterName': 'kafka-1'}, status={})
        resources = [kafka, topic]
        _nest_topics_under_kafka(resources)
        assert topic not in resources
        assert topic in kafka.children

    def test_orphan_topic_stays_at_top_level(self):
        topic = ResourceNode(name='topic-1', plural='capturedtraffics', phase='Ready',
                             depends_on=[], spec={'kafkaClusterName': 'nonexistent'}, status={})
        resources = [topic]
        _nest_topics_under_kafka(resources)
        assert topic in resources


# --- format_spec_fields ---

class TestFormatSpecFields:
    def test_displays_configured_fields(self):
        resource = make_resource('captureproxies', spec={
            'podReplicas': 2, 'listenPort': 9201, 'internetFacing': True,
        })
        lines = format_spec_fields(resource)
        assert 'podReplicas: 2' in lines
        assert 'listenPort: 9201' in lines
        assert 'internetFacing: True' in lines

    def test_skips_empty_and_none_values(self):
        resource = make_resource('datasnapshots', spec={
            'snapshotPrefix': 'backfill', 'indexAllowlist': [],
        })
        lines = format_spec_fields(resource)
        assert any('snapshotPrefix: backfill' in ln for ln in lines)
        # Empty list should be skipped
        assert not any('indexAllowlist' in ln for ln in lines)

    def test_nested_field_path(self):
        resource = make_resource('kafkaclusters', spec={
            'auth': {'type': 'NONE'}, 'version': '3.6',
        })
        lines = format_spec_fields(resource)
        assert 'version: 3.6' in lines
        assert 'type: NONE' in lines

    def test_list_value_truncated(self):
        resource = make_resource('datasnapshots', spec={
            'snapshotPrefix': 'test',
            'indexAllowlist': ['idx1', 'idx2', 'idx3', 'idx4'],
        })
        lines = format_spec_fields(resource)
        allowlist_line = [ln for ln in lines if 'indexAllowlist' in ln][0]
        assert 'idx1, idx2, idx3...' in allowlist_line

    def test_unknown_plural_returns_empty(self):
        resource = make_resource('unknowntype', spec={'foo': 'bar'})
        assert format_spec_fields(resource) == []


class TestConfigOverlays:
    def test_attaches_pending_and_to_submit_values(self):
        resource = make_resource(
            'kafkaclusters',
            name='default',
            spec={'version': '3.6.0', 'auth': {'type': 'none'}},
        )
        sections = [ResourceSection(name='Live Traffic Migration', groups=[
            ResourceGroup(plural='kafkaclusters', display_name='Buffer', resources=[resource])
        ])]

        submitted = {'resources': [{
            'kind': 'KafkaCluster',
            'name': 'default',
            'parameters': {'version': '3.7.0', 'auth': {'type': 'none'}},
        }]}
        pending = {'resources': [{
            'kind': 'KafkaCluster',
            'name': 'default',
            'parameters': {'version': '3.8.0', 'auth': {'type': 'none'}},
        }]}

        apply_config_overlays(sections, submitted, pending)

        assert resource.config_diff['has_submitted_changes'] is True
        assert resource.config_diff['has_pending_submit_changes'] is True
        assert format_config_diff_fields(resource) == [
            'version: deployed=3.6.0 | pending=3.7.0 | to-submit=3.8.0'
        ]
        assert format_config_diff_fields(resource, 'pendingSubmit') == ['version: to-submit=3.8.0']

    def test_adds_virtual_resource_from_saved_config(self):
        sections = _build_tree_from_raw({})
        pending = {'resources': [{
            'kind': 'TrafficReplay',
            'name': 'replay-new',
            'parameters': {'podReplicas': 2, 'speedupFactor': 1.5},
        }]}

        apply_config_overlays(sections, pending_resolved_config=pending)

        replay_group = sections[1].groups[2]
        assert len(replay_group.resources) == 1
        resource = replay_group.resources[0]
        assert resource.name == 'replay-new'
        assert resource.phase == 'Pending Config'
        assert resource.config_presence == {'deployed': False, 'pending': True}
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_PENDING_SUBMIT) is True
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_DEPLOYED) is False
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_CURRENT_WORKFLOW) is False
        assert 'podReplicas: deployed=<absent> | pending=<absent> | to-submit=2' in format_config_diff_fields(resource)

    def test_adds_partial_loose_resource_and_clears_not_configured_placeholder(self):
        sections = _build_tree_from_raw({})
        capture_group = sections[1].groups[0]
        capture_group.not_configured = True
        pending = {'resources': [{
            'kind': 'CaptureProxy',
            'name': 'capture-new',
            'parameters': {'dependsOn': ['capture-new-topic']},
            'diagnostics': [{
                'severity': 'required',
                'path': ['traffic', 'proxies', 'capture-new', 'proxyConfig'],
                'message': 'Invalid input: expected object, received undefined',
            }],
        }]}

        apply_config_overlays(sections, pending_resolved_config=pending)

        assert capture_group.not_configured is False
        assert len(capture_group.resources) == 1
        resource = capture_group.resources[0]
        assert resource.name == 'capture-new'
        assert resource.phase == 'Pending Config'
        assert resource.diagnostics == [{
            'severity': 'required',
            'path': ['traffic', 'proxies', 'capture-new', 'proxyConfig'],
            'message': 'Invalid input: expected object, received undefined',
        }]
        assert format_resource_diagnostics(resource) == [{
            'severity': 'required',
            'label': 'required: traffic.proxies.capture-new.proxyConfig: Invalid input: expected object, received undefined',
        }]

    def test_existing_resource_absent_from_saved_config_is_marked_for_delete(self):
        resource = make_resource('trafficreplays', name='replay-old', spec={'podReplicas': 2})
        sections = [ResourceSection(name='Live Traffic Migration', groups=[
            ResourceGroup(plural='trafficreplays', display_name='Replay', resources=[resource])
        ])]
        pending = {'resources': []}

        apply_config_overlays(sections, pending_resolved_config=pending)

        assert resource.config_presence == {'deployed': True, 'pending': False}
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_DEPLOYED) is True
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_PENDING_SUBMIT) is False
        assert format_config_diff_fields(resource) == [
            'podReplicas: deployed=2 | pending=2 | to-submit=<absent>'
        ]

    def test_existing_resource_absent_from_submitted_config_is_marked_for_delete(self):
        resource = make_resource('trafficreplays', name='replay-old', spec={'podReplicas': 2})
        sections = [ResourceSection(name='Live Traffic Migration', groups=[
            ResourceGroup(plural='trafficreplays', display_name='Replay', resources=[resource])
        ])]
        submitted = {'resources': []}

        apply_config_overlays(sections, submitted_resolved_config=submitted)

        assert resource.config_presence == {'deployed': True, 'submitted': False}
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_DEPLOYED) is True
        assert resource_visible_in_config_mode(resource, CONFIG_MODE_CURRENT_WORKFLOW) is False
        assert format_config_diff_fields(resource) == [
            'podReplicas: deployed=2 | pending=<absent> | to-submit=<absent>'
        ]

    def test_adds_virtual_source_config_from_console_resources(self):
        sections = _build_tree_from_raw({})
        submitted_console = {'sources': [{
            'refName': 'legacy',
            'clientConfig': {'endpoint': 'https://old.example.com', 'allow_insecure': False},
        }]}
        pending_console = {'sources': [{
            'refName': 'legacy',
            'clientConfig': {'endpoint': 'https://new.example.com', 'allow_insecure': False},
        }]}

        apply_config_overlays(
            sections,
            submitted_console_config=submitted_console,
            pending_console_config=pending_console,
        )

        config_section = sections[0]
        assert config_section.name == 'Workflow Configuration'
        source_group = config_section.groups[0]
        resource = source_group.resources[0]
        assert resource.name == 'legacy'
        assert resource.plural == 'sourceconfigs'
        assert format_config_diff_fields(resource) == [
            'endpoint: deployed=<absent> | pending=https://old.example.com | to-submit=https://new.example.com'
        ]


# --- format_live_status ---

class TestFormatLiveStatus:
    def test_backfill_running_shows_progress(self):
        resource = make_resource('snapshotmigrations', status={
            'documentBackfill': {
                'phase': 'Running',
                'summary': {
                    'percentageCompleted': 45.5,
                    'shardsTotal': 10,
                    'shardsMigrated': 4,
                    'shardsInProgress': 2,
                    'shardsWaiting': 4,
                    'etaMs': 381000,
                },
                'updatedAt': '2026-06-01T12:00:00Z',
            }
        })
        result = format_live_status(resource)
        assert result is not None
        summary, details = result
        assert 'Backfill status:' in summary
        assert '46%' in summary
        assert 'shards 4/10' in summary
        assert 'ETA' in summary
        assert any('phase: Running' in d for d in details)
        assert any('shards in progress: 2' in d for d in details)

    def test_backfill_completed(self):
        resource = make_resource('snapshotmigrations', status={
            'documentBackfill': {
                'phase': 'Completed',
                'summary': {
                    'percentageCompleted': 100,
                    'shardsTotal': 10,
                    'shardsMigrated': 10,
                    'started': '2026-06-01T10:00:00Z',
                    'finished': '2026-06-01T11:00:00Z',
                },
            }
        })
        summary, details = format_live_status(resource)
        assert '100%' in summary
        assert any('started:' in d for d in details)
        assert any('finished:' in d for d in details)

    def test_snapshot_creation_status(self):
        resource = make_resource('datasnapshots', status={
            'snapshotCreation': {
                'phase': 'Running',
                'summary': {'shardsTotal': 20, 'shardsSuccessful': 15, 'shardsFailed': 1,
                            'dataProcessed': 512, 'dataProcessedUnit': 'MiB'},
                'updatedAt': '2026-06-01T12:00:00Z',
            }
        })
        summary, details = format_live_status(resource)
        assert 'Snapshot status:' in summary
        assert 'shards 15/20' in summary
        assert any('shards failed: 1' in d for d in details)
        assert any('data processed: 512 MiB' in d for d in details)

    def test_capture_proxy_endpoint(self):
        resource = make_resource('captureproxies', status={
            'loadBalancerEndpoint': 'abc123.elb.amazonaws.com',
        })
        summary, details = format_live_status(resource)
        assert 'endpoint: abc123.elb.amazonaws.com' in summary
        assert details == []

    def test_no_status_returns_none(self):
        resource = make_resource('snapshotmigrations', status={})
        assert format_live_status(resource) is None

    def test_empty_backfill_summary_returns_none(self):
        resource = make_resource('snapshotmigrations', status={
            'documentBackfill': {'summary': {}}
        })
        assert format_live_status(resource) is None


# --- maybe_rewrite_wait_step ---

class TestMaybeRewriteWaitStep:
    def test_rewrite_wait_with_resource_name(self):
        step = {
            'display_name': 'waitForDataSnapshot(0:configChecksum:abc,resourceName:my-snap)',
            'inputs': {'parameters': [{'name': 'resourceName', 'value': 'my-snap'}]},
        }
        result = maybe_rewrite_wait_step(step)
        assert result['display_name'] == 'Waiting for: my-snap'

    def test_rewrite_waitIndefinitely_with_resource_name(self):
        step = {
            'display_name': 'waitIndefinitelyForSnapshotMigrationDeps(0:resourceName:backfill)',
            'inputs': {'parameters': [{'name': 'resourceName', 'value': 'backfill'}]},
        }
        result = maybe_rewrite_wait_step(step)
        assert result['display_name'] == 'Waiting for: backfill'

    def test_non_wait_step_unchanged(self):
        step = {'display_name': 'readSnapshotName', 'inputs': {'parameters': []}}
        result = maybe_rewrite_wait_step(step)
        assert result['display_name'] == 'readSnapshotName'

    def test_approval_node_not_rewritten(self):
        step = {
            'display_name': 'waitForUserApproval',
            'templateRef': {'name': 'resource-management', 'template': 'waitforuserapproval'},
            'inputs': {'parameters': [{'name': 'resourceName', 'value': 'test'}]},
        }
        result = maybe_rewrite_wait_step(step)
        assert result['display_name'] == 'waitForUserApproval'

    def test_wait_without_resource_name_strips_params(self):
        step = {
            'display_name': 'waitForSomething(0:param:value)',
            'inputs': {'parameters': []},
        }
        result = maybe_rewrite_wait_step(step)
        assert result['display_name'] == 'waitForSomething'


# --- has_notable_steps / collect_notable_steps ---

class TestNotableSteps:
    def test_running_step_is_notable(self):
        steps = [{'phase': 'Running', 'display_name': 'step1'}]
        assert has_notable_steps(steps) is True
        assert len(collect_notable_steps(steps)) == 1

    def test_succeeded_step_not_notable(self):
        steps = [{'phase': 'Succeeded', 'display_name': 'step1'}]
        assert has_notable_steps(steps) is False
        assert collect_notable_steps(steps) == []

    def test_nested_notable_child_lifts_up(self):
        steps = [{'phase': 'Succeeded', 'display_name': 'parent', 'children': [
            {'phase': 'Failed', 'display_name': 'child'}
        ]}]
        assert has_notable_steps(steps) is True
        notable = collect_notable_steps(steps)
        assert len(notable) == 1
        assert notable[0]['display_name'] == 'child'

    def test_failed_step_is_notable(self):
        steps = [{'phase': 'Failed', 'display_name': 'broken'}]
        assert has_notable_steps(steps) is True


class TestFindLastSucceeded:
    def test_returns_most_recent(self):
        steps = [
            {'phase': 'Succeeded', 'display_name': 'a', 'finished_at': '2026-01-01T10:00:00Z'},
            {'phase': 'Succeeded', 'display_name': 'b', 'finished_at': '2026-01-01T11:00:00Z'},
            {'phase': 'Running', 'display_name': 'c'},
        ]
        result = find_last_succeeded(steps)
        assert result['display_name'] == 'b'

    def test_returns_none_when_no_succeeded(self):
        steps = [{'phase': 'Running', 'display_name': 'c'}]
        assert find_last_succeeded(steps) is None


# --- mark_not_configured_groups ---

class TestMarkNotConfiguredGroups:
    def test_marks_skipped_groups(self):
        sections = _build_tree_from_raw({})
        filtered_tree = [
            {'display_name': 'createKafka', 'phase': 'Skipped'},
            {'display_name': 'createProxy', 'phase': 'Skipped'},
        ]
        mark_not_configured_groups(sections, filtered_tree)
        # kafkaclusters and captureproxies should be marked
        live_section = sections[1]
        capture_group = next(g for g in live_section.groups if g.plural == 'captureproxies')
        kafka_group = next(g for g in live_section.groups if g.plural == 'kafkaclusters')
        assert capture_group.not_configured is True
        assert kafka_group.not_configured is True

    def test_non_skipped_groups_not_marked(self):
        sections = _build_tree_from_raw({})
        filtered_tree = [{'display_name': 'createKafka', 'phase': 'Running'}]
        mark_not_configured_groups(sections, filtered_tree)
        live_section = sections[1]
        kafka_group = next(g for g in live_section.groups if g.plural == 'kafkaclusters')
        assert kafka_group.not_configured is False

    def test_empty_filtered_tree_no_op(self):
        sections = _build_tree_from_raw({})
        mark_not_configured_groups(sections, [])
        for s in sections:
            for g in s.groups:
                assert g.not_configured is False
