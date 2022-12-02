import json
import pytest

from upgrade_testing_framework.core.framework_state import FrameworkState, get_initial_state
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

def test_WHEN_FrameworkState_object_used_THEN_works_expected():
    # Run our test
    state = FrameworkState({})
    state.set('Fingon', 'Son of Fingolfin')

    # Check our results
    assert 'Son of Fingolfin' == state.get('Fingon')

def test_WHEN_FrameworkState_object_has_starting_value_THEN_can_retrieve_it():
    # Run our test
    state = FrameworkState({'Fingon': 'Son of Fingolfin'})

    # Check our results
    assert 'Son of Fingolfin' == state.get('Fingon')

def test_WHEN_get_initial_state_called_AND_new_run_THEN_behaves_as_expected(tmpdir):
    # Test values
    base_directory = tmpdir.join('utf')
    workspace = WorkspaceWrangler(base_directory=base_directory.strpath)

    # Run our test
    test_result = get_initial_state(workspace, 'FirstStep', False)

    # Check our results
    assert test_result._state == {
        "current_step": "FirstStep"
    }

def test_WHEN_get_initial_state_called_AND_resuming_THEN_behaves_as_expected(tmpdir):
    # Test values
    base_directory = tmpdir.join('utf')
    workspace = WorkspaceWrangler(base_directory=base_directory.strpath)
    previous_state = {
        "current_step": "SecondStep",
        "test_state": "Test"
    }
    with open(workspace.state_file, 'w') as file_handle:
        json.dump(previous_state, file_handle)

    # Run our test
    test_result = get_initial_state(workspace, 'FirstStep', True)

    # Check our results
    assert test_result._state == {
        "current_step": "SecondStep",
        "test_state": "Test"
    }