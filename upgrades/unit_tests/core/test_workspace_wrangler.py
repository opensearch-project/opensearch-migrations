import os
import pytest

from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

def test_WHEN_workspace_wrangler_used_THEN_workspace_created_as_expected(tmpdir):
    # Test values
    base_directory = tmpdir.join('utf')

    # Run our test
    wrangler = WorkspaceWrangler(base_directory=base_directory.strpath)

    # Check our results
    logs_directory = base_directory.join('logs')

    assert logs_directory.check(dir=1)
    assert os.path.join(base_directory.strpath, 'state-file') == wrangler.state_file