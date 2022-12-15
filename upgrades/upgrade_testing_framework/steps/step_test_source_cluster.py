import json

from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.shell_interactions as shell


class TestSourceCluster(FrameworkStep):
    """
    This step is where you run tests on the source cluster
    """

    def _run(self):
        # Get the state we need
        source_cluster = self.state.source_cluster

        # Begin the step body
        self.logger.info("Querying cluster status...")
        port = source_cluster.rest_ports[0]
        _, output = shell.call_shell_command(f"curl -X GET \"localhost:{port}/_cat/nodes?v=true&pretty\"")
        self.logger.info("\n".join(output))
        
        self.logger.info("Uploading sample document...")
        put_command = f"curl -X PUT 'localhost:{port}/noldor/_doc/1?pretty' -H 'Content-Type: application/json' -d'{{\"name\": \"Finwe\"}}'"
        _, _ = shell.call_shell_command(put_command)

        self.logger.info("Retrieving uploaded document...")
        get_command = f"curl -X GET 'localhost:{port}/noldor/_doc/1?pretty'"
        _, output = shell.call_shell_command(get_command)
        if "Finwe" in "\n".join(output):
            self.logger.info("Retrieved uploaded doc sucessfully")
            snapshot_get = json.loads("".join(output))
            self.logger.info(json.dumps(snapshot_get, sort_keys=True, indent=4))
        else:
            self.fail("Unable to retrieve uploaded doc")
        
        # Update our state
        # N/A
        