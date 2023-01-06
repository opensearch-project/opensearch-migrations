from abc import ABC, abstractmethod
import logging
from pathlib import Path
from typing import List


class ActionsUnexecutedExeception(Exception):
    def __init__(self):
        super().__init__("The actions associated with this ActionExecuter have not yet been executed")


class ActionExecutor(ABC):
    """
    Base class used to execute sets of operations using the Robot Framework and encapsulate the results via a path to
    the output.xml file associated with them.
    """
    def __init__(self, actions_dir: Path, output_dir: Path, include_tags: List[str] = [],
                 exclude_tags: List[str] = [], console_width: int = 100, log_level: str = "WARN"):
        self.actions_dir = actions_dir
        self.output_dir = output_dir
        self.include_tags = include_tags
        self.exclude_tags = exclude_tags
        self.console_width = console_width
        self.log_level = log_level

        self._output_xml_path: Path = None

    def to_dict(self) -> dict:
        return {
            "actions_dir": str(self.actions_dir),
            "output_dir": str(self.output_dir),
            "include_tags": self.include_tags,
            "exclude_tags": self.exclude_tags,
            "console_width": self.console_width,
            "log_level": self.log_level,
            "output_xml_path": str(self._output_xml_path)
        }

    def execute(self):
        # Robot Framework is naughty and changes the root logger level when you ask it to run at a specific level.
        # This means if the level we want it to run at is different than our existing root logger, we need to cache the
        # exist level.
        root_logger_level = logging.getLogger().getEffectiveLevel()

        # Run the actions
        self._execute()

        # Reset the root logger level to what it was at the start
        logging.getLogger().setLevel(root_logger_level)

        # Store a Path to the results
        self._output_xml_path = self.output_dir.joinpath("output.xml")

    @abstractmethod
    def _execute(self):
        """
        Define a concretion and invoke the actual actions here, probably by invoking the robot.run() API
        """
        pass

    @property
    def output_xml_path(self) -> Path:
        if self._output_xml_path is None:
            raise ActionsUnexecutedExeception()
        return self._output_xml_path
