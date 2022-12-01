import logging
import py
import pytest

from upgrade_testing_framework.core.logging_wrangler import LoggingWrangler
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

@pytest.fixture
def vingilot_workspace(tmpdir):
    return WorkspaceWrangler(base_directory=tmpdir.strpath)

def test_WHEN_logging_wrangler_used_THEN_logged_to_file_as_expected(vingilot_workspace):
    # Test values
    test_string = 'Utulie\'n aure! Aiya Eldalie ar Atanatari, utulie\'n aure! The day has come! Behold, people of the Eldar and Fathers of Men, the day has come!'

    # Run our test
    wrangler = LoggingWrangler(vingilot_workspace)
    logging.debug(test_string)

    # Check our results
    log_file = py.path.local(wrangler.log_file)
    contents = log_file.read_text('utf8')
    assert test_string in contents