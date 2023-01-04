from cluster_migration_core.core.framework_state import FrameworkState, get_initial_state


def test_WHEN_FrameworkState_object_used_THEN_works_expected():
    # Run our test
    state = FrameworkState({})
    state.set_key('Fingon', 'Son of Fingolfin')

    # Check our results
    assert 'Son of Fingolfin' == state.get_key('Fingon')


def test_WHEN_FrameworkState_object_has_starting_value_THEN_can_retrieve_it():
    # Run our test
    state = FrameworkState({'Fingon': 'Son of Fingolfin'})

    # Check our results
    assert 'Son of Fingolfin' == state.get_key('Fingon')


def test_WHEN_get_initial_state_called_AND_new_run_THEN_behaves_as_expected(tmpdir):
    # Test values
    test_config_path = "./path/to/test_config.json"

    # Run our test
    test_result = get_initial_state(test_config_path)

    # Check our results
    assert test_result._app_state == {
        "test_config_path": test_config_path
    }
