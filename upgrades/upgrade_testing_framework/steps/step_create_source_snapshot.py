import json

from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.shell_interactions as shell

class CreateSourceSnapshot(FrameworkStep):
    """
    This step creates the snapshot on the source cluster.
    """

    def _run(self):
        """
        The contents of this are quick/dirty.  Instead of raw curl commands we should probably be using the Python SDK
        for Elasticsearch/OpenSearch.  At the very least, we should wrap the curl commands in our own pseudo-client.
        """

        # Get the state we need
        shared_volume = self.state.shared_volume
        source_cluster = self.state.source_cluster

        # Begin the step body
        self.logger.info("Creating snapshot of source cluster...")
        port = source_cluster.rest_ports[0]

        register_cmd = f"""curl -XPUT 'localhost:{port}/_snapshot/noldor' -H 'Content-Type: application/json' -d '{{
            "type": "fs",
                "settings": {{
                    "location": "{shared_volume.mount_point}"
            }}
        }}'
        """
        _, _ = shell.call_shell_command(register_cmd)

        create_cmd = f"curl -XPUT 'localhost:{port}/_snapshot/noldor/1'"
        _, _ = shell.call_shell_command(create_cmd)

        self.logger.info("Confirming snapshot of source cluster exists...")
        confirm_cmd = f"curl 'localhost:{port}/_snapshot/noldor/1?pretty' -ku 'admin:admin'"
        _, output = shell.call_shell_command(confirm_cmd)
        if "noldor" in "\n".join(output):
            self.logger.info("Source snapshot created successfully")
            snapshot_get = json.loads("".join(output))
            self.logger.info(json.dumps(snapshot_get, sort_keys=True, indent=4))
        else:
            self.fail("Snapshot creation failed")
        
        # Update our state
        # N/A
        
