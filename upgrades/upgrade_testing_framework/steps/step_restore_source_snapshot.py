import json
import time

from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.shell_interactions as shell

class RestoreSourceSnapshot(FrameworkStep):
    """
    This step restores the source snapshot on the target cluster and confirms it worked.
    """

    def _run(self):
        """
        The contents of this are quick/dirty.  Instead of raw curl commands we should probably be using the Python SDK
        for Elasticsearch/OpenSearch.  At the very least, we should wrap the curl commands in our own pseudo-client.
        """

        # Get the state we need
        shared_volume = self.state.shared_volume
        target_cluster = self.state.target_cluster

        # Begin the step body
        self.logger.info("Checking if source snapshots are visible on target...")
        port = target_cluster.rest_ports[0]

        register_cmd = f"""curl -X PUT 'localhost:{port}/_snapshot/noldor' -H 'Content-Type: application/json' -d '{{
            "type": "fs",
                "settings": {{
                    "location": "{shared_volume.mount_point}"
            }}
        }}'
        """
        _, _ = shell.call_shell_command(register_cmd)

        check_cmd = f"curl -X GET 'localhost:{port}/_snapshot/noldor/_all?pretty' -ku 'admin:admin'"
        _, output = shell.call_shell_command(check_cmd)
        if "noldor" in "\n".join(output):
            self.logger.info("Source snapshot visible to target cluster")
        else:
            self.fail("Snapshot restoration failed; source snapshots not visible to target cluster")

        self.logger.info("Restoring source snapshot onto target...")
        restore_cmd = f"curl -X POST 'localhost:{port}/_snapshot/noldor/1/_restore?pretty' -ku 'admin:admin'"
        _, _ = shell.call_shell_command(restore_cmd)

        self.logger.info("Waiting a few seconds for the snapshot to be restored...")
        time.sleep(5)

        self.logger.info("Attempting to retrieve source doc from target cluster...")
        get_cmd = f"curl -X GET 'localhost:{port}/noldor/_doc/1?pretty'"
        _, output = shell.call_shell_command(get_cmd)
        if "Finwe" in "\n".join(output):
            self.logger.info("Document retrieved, snapshot restored successfully")
            snapshot_get = json.loads("".join(output))
            self.logger.info(json.dumps(snapshot_get, sort_keys=True, indent=4))
        else:
            self.fail("Snapshot restoration failed; document from source cluster unable to be retrieved")
        
        # Update our state
        # N/A
        
