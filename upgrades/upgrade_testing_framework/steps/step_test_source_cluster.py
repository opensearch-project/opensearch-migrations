from upgrade_testing_framework.clients.rest_client_default import RESTClientDefault
from upgrade_testing_framework.core.framework_step import FrameworkStep


class TestSourceCluster(FrameworkStep):
    """
    This step is where you run tests on the source cluster.  The code currently in here is for demo purposes only, and
    will be deleted once we've incorporated the Robot tests into the UTF.
    """

    def _run(self):
        # Get the state we need
        source_cluster = self.state.source_cluster

        # Begin the step body
        port = source_cluster.rest_ports[0]
        rest_client = RESTClientDefault()

        self.logger.info("Querying cluster status...")
        response_status = rest_client.get_nodes_status(port)
        self.logger.info(response_status.response_text)
        
        self.logger.info("Uploading sample document...")
        sample_doc = {"name": "Finwe"}
        response_doc_post = rest_client.post_doc_to_index(port, "noldor", sample_doc)
        self.logger.info(response_doc_post.response_text)

        self.logger.info("Retrieving uploaded document...")
        sample_doc_id = response_doc_post.response_json["_id"]
        response_status = rest_client.get_doc_by_id(port, "noldor", sample_doc_id)
        if "Finwe" in response_status.response_text:
            self.logger.info("Retrieved uploaded doc sucessfully")
            self.logger.info(response_status.response_text)
        else:
            self.fail("Unable to retrieve uploaded doc")
        
        # Update our state
        self.state.source_doc_id = sample_doc_id # Quick hack, will replace w/ better solution
        