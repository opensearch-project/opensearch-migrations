import coloredlogs
from datetime import datetime
import logging
import os

from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

class FrameworkLoggingAdapter(logging.LoggerAdapter):
    # Print the name of the currently-executing step, then the log message
    def process(self, msg, kwargs):
        return "[{}] {}".format(self.extra['step'], msg), kwargs

class FrameworkLoggingFormatter(logging.Formatter):
     def formatTime(self, record, datefmt=None):
        return datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S.%f')

LINE_SEP = '\u2063' # Invisible Unicode character.  Makes figuring out the beginning/end of a log entry much
            # easier, given they can contain new line characters themselves.

class LoggingWrangler:
    def __init__(self, workspace: WorkspaceWrangler):
        logfile_base_path = os.path.join(workspace.logs_directory, self._default_log_name())

        date_suffix = "{:%Y-%m-%d_%H_%M_%S}".format(datetime.now())
        self._current_log_file = "{}.{}".format(logfile_base_path, date_suffix)
        self._initialize_logging()

    def _initialize_logging(self):
        # Write high level logs intended for humans to stdout, and low-level debug logs to a file.
        
        root_logger = logging.getLogger()
        root_logger.handlers = [] # Make sure we're starting with a clean slate
        root_logger.setLevel(logging.DEBUG)

        # Send INFO+ level logs to stdout, and enable colorized messages
        console_handler = logging.StreamHandler()
        console_handler.setLevel(logging.INFO)
        console_formatter = coloredlogs.ColoredFormatter('%(message)s')
        console_handler.setFormatter(console_formatter)
        root_logger.addHandler(console_handler)

        # Send DEBUG+ level logs to a timestamped logfile for a historical record of the invocation
        file_handler_timestamped = logging.FileHandler(self._current_log_file, mode='w', encoding='utf8')
        file_handler_timestamped.setLevel(logging.DEBUG)
        file_formatter = FrameworkLoggingFormatter(f"%(asctime)s - %(name)s - %(message)s{LINE_SEP}")
        file_handler_timestamped.setFormatter(file_formatter)
        root_logger.addHandler(file_handler_timestamped)

    def _default_log_name(self):
        return 'run.log'

    @property
    def log_file(self):
        return self._current_log_file