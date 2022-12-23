import os

DEFAULT_BASE_DIR = '/tmp/utf' #arbitrarily chosen

class WorkspaceWrangler:
    """
    This class is used to set up and track the directory tree that stores the framework's logs, state file, and any
    other resources on disk.
    """
    def __init__(self, base_directory=DEFAULT_BASE_DIR):
        self.base_directory = base_directory
        self._ensure_workspace_exists()

    def _ensure_workspace_exists(self):
        if not os.path.exists(self.utf_base_directory):
            print("Base directory ({}) doesn't exist, creating...".format(self.utf_base_directory))
            os.makedirs(self.utf_base_directory)
            
        if not os.path.exists(self.logs_directory):
            print("Logs directory ({}) doesn't exist, creating...".format(self.logs_directory))
            os.makedirs(self.logs_directory)

    @property
    def logs_directory(self):
        return os.path.join(self.utf_base_directory, 'logs')

    @property
    def state_file(self):
        return os.path.join(self.utf_base_directory, 'state-file')

    @property
    def test_results_pre_upgrade_directory(self):
        return os.path.join(self.utf_base_directory, 'test-results', 'pre-upgrade')

    @property
    def test_results_post_upgrade_directory(self):
        return os.path.join(self.utf_base_directory, 'test-results', 'post-upgrade')

    @property
    def utf_base_directory(self):
        return self.base_directory