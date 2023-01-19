import robot

from cluster_migration_core.robot_actions.action_executor import ActionExecutor


class ClusterActionExecutor(ActionExecutor):
    """
    Use this class to execute operations against a cluster
    """

    def __init__(self, hostname: str, port: int, engine_version: str, *args, **kwargs):
        self.hostname = hostname
        self.port = port
        self.engine_version = engine_version
        super().__init__(*args, **kwargs)

    def _execute(self) -> None:
        robot.run(
            self.actions_dir,
            include=self.include_tags,
            exclude=self.exclude_tags,
            outputdir=self.output_dir,
            variable=[f"host:{self.hostname}", f"port:{self.port}", f"engine_version:{self.engine_version}"],
            consolewidth=self.console_width,
            loglevel=self.log_level
        )
