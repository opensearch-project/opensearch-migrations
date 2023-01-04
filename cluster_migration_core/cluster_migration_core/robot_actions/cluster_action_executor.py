from robot import run

from cluster_migration_core.robot_actions.action_executor import ActionExecutor


class ClusterActionExecutor(ActionExecutor):
    """
    Use this class to execute operations against a cluster
    """

    def __init__(self, hostname: str, port: int, *args, **kwargs):
        self.hostname = hostname
        self.port = port
        super().__init__(*args, **kwargs)

    def _execute(self) -> None:
        run(
            self.actions_dir,
            include=self.include_tags,
            exclude=self.exclude_tags,
            outputdir=self.output_dir,
            variable=[f"host:{self.hostname}", f"port:{self.port}"],
            consolewidth=self.consolewidth,
            loglevel=self.loglevel
        )
