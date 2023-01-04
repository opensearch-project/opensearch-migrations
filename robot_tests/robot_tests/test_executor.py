import logging
import os
from robot import run
package_directory = os.path.dirname(os.path.abspath(__file__))


class TestExecutor:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port

    def execute_tests(self, include_tags=[], exclude_tags=[], output_dir="test_results"):
        # Robot Framework is naughty and changes the root logger level when you ask it to run at a specific level.
        # This means if the level we want it to run at is different than our existing root logger, we need to cache the
        # exist level.
        root_logger_level = logging.getLogger().getEffectiveLevel()

        # Run the tests
        run(
            f"{package_directory}/tests",
            include=include_tags,
            exclude=exclude_tags,
            outputdir=output_dir,
            variable=[f"host:{self.host}", f"port:{self.port}"],
            loglevel="WARN",  # only see test results and when things go wrong
            consolewidth=100  # arbitrarily chosen, but looks good and doesn't truncate output
        )

        # Reset the root logger level to what it was at the start
        logging.getLogger().setLevel(root_logger_level)
