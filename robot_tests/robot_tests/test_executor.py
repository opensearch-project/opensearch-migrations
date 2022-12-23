import os
from robot import run
package_directory = os.path.dirname(os.path.abspath(__file__))


class TestExecutor:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port

    def execute_tests(self, include_tags=[], exclude_tags=[], output_dir="test_results"):
        # Temporary measure here to use default rest client path, logic to be added here which will dynamically choose the client
        # libraries needed based on upgrade_testing_clients package and passed EngineVersion
        run(f"{package_directory}/tests", include=include_tags, exclude=exclude_tags, outputdir=output_dir,
            variable=[f"host:{self.host}", f"port:{self.port}", "rest_client:upgrade_testing_clients.rest_client_default.RESTClientDefault"])
