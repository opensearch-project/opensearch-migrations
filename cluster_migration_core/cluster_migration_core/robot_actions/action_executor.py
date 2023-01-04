from abc import ABC, abstractmethod
import logging
from pathlib import Path
from typing import List

from robot.api import ExecutionResult


class ActionExecutor (ABC):
    """
    Base class used to execute sets of operations using the Robot Framework and return the results.
    """
    def __init__(self, actions_dir: Path, output_dir: Path, include_tags: List[str] = [],
                 exclude_tags: List[str] = [], consolewidth: int = 100, loglevel: str = "WARN"):
        self.actions_dir = actions_dir
        self.output_dir = output_dir
        self.include_tags = include_tags
        self.exclude_tags = exclude_tags
        self.consolewidth = consolewidth
        self.loglevel = loglevel

        self.output_xml_path = self.output_dir.joinpath("output.xml")
        self.result: ExecutionResult = None

    def execute(self):
        # Robot Framework is naughty and changes the root logger level when you ask it to run at a specific level.
        # This means if the level we want it to run at is different than our existing root logger, we need to cache the
        # exist level.
        root_logger_level = logging.getLogger().getEffectiveLevel()

        # Run the actions
        self._execute()

        # Reset the root logger level to what it was at the start
        logging.getLogger().setLevel(root_logger_level)

        # Parse and return the results of the tests
        self.result = ExecutionResult(self.output_xml_path)

    @abstractmethod
    def _execute(self):
        """
        Define a concretion and invoke the actual actions here, probably by invoking the robot.run() API
        """
        pass
