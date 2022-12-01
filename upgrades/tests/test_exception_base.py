import pytest

import upgrade_testing_framework.core.exception_base as exceptions

def test_WHEN_is_exception_in_type_list_called_AND_is_not_in_list_THEN_returns_false():
    # Run our test
    actual_value = exceptions.is_exception_in_type_list(KeyboardInterrupt(), [RuntimeError])

    # Check the results
    expected_value = False
    assert expected_value == actual_value

def test_WHEN_is_exception_in_type_list_called_AND_empty_list_THEN_returns_false():
    # Run our test
    actual_value = exceptions.is_exception_in_type_list(KeyboardInterrupt(), [])

    # Check the results
    expected_value = False
    assert expected_value == actual_value

def test_WHEN_is_exception_in_type_list_called_AND_is_in_list_THEN_returns_true():
    # Run our test
    actual_value = exceptions.is_exception_in_type_list(KeyboardInterrupt(), [KeyboardInterrupt])

    # Check the results
    expected_value = True
    assert expected_value == actual_value