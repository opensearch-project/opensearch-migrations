# Capture Proxy

## Installing Capture Proxy on Coordinator nodes

Follow documentation for [deploying solution](../../deployment/README.md) to set up the Kafka cluster which the Capture Proxy will send captured traffic to. Then, following the steps below, attach the Capture Proxy on each coordinator node of the source cluster, so that all incoming traffic can be captured. If the source cluster has more than one coordinator node, this can be done in a rolling restart fashion where there is no downtime for the source cluster, otherwise a single node cluster should expect downtime as the Elasticsearch/OpenSearch process restarts 

**Note**: This is one method for installing the Capture Proxy on a node, and that these steps may vary depending on your environment.

LINK TO AWS ADDITIONAL SETUP

### Follow these steps to attach a Capture Proxy on the node.

1. Log in to one of the coordinator nodes for command line access.
2. Locate the elasticsearch/opensearch directory
   * On EC2 this may look like `/home/ec2-user/elasticsearch`
3. Update node’s port setting in elasticsearch.yml/opensearch.yml, e.g. `/home/ec2-user/elasticsearch/config/elasticsearch.yml`.
   * Add this line to the node’s config file: `http.port: 19200`
   * This will allow incoming traffic to enter through the Capture proxy at the normal port (typically `9200`) and then be passed to elasticsearch/opensearch at the `19200` port
4. Restart Elasticsearch/OpenSearch process so that the process will bind to the newly configured port. 
   * Depending on the installation method used, how you should restart Elasticsearch/OpenSearch will differ:
      * For **Debian packages with systemctl** (Most common) you can run the following command: `sudo systemctl restart elasticsearch.service`
      * For **Debian packages with service** you can run the following command: `sudo -i service elasticsearch restart`
      * For **Tarball** you should find the running process (e.g. `ps aux | grep elasticsearch`) and stop it. Then start the process again as you normally do.
5. Verify process is bound to new port. Run `netstat -tapn | grep LISTEN` to see if the new port is being listened on and that the old port is no longer there.
   * If the new port is not there, then there is a chance that Elasticsearch/OpenSearch is not running, in that case, you must start the process again and verify that it has started properly.
6. Test the new port by sending any kind of traffic or request, e.g. `curl https://localhost:19200` or if security is not enabled `curl http://localhost:19200`
7. Verify the JAVA_HOME environment variable has been set (`echo $JAVA_HOME`) as this will be necessary for the Capture Proxy to execute. 
   * If Java is not already available on your node, your elasticsearch/opensearch folder may have a bundled JDK you can use, e.g. `export JAVA_HOME=/home/ec2-user/elasticsearch/jdk`
8. Download the Capture Proxy tar file
   1. Go to the `opensearch-migrations` repository's latest releases page: https://github.com/opensearch-project/opensearch-migrations/releases/latest
   2. Copy the link for the Capture Proxy tar file, mind your node’s architecture.
   3. Download the tar file `curl -L0 <CAPTURE-PROXY-TAR-LINK> --output CaptureProxyX64.tar.gz`
   4. Unpack the tar file: `tar -xvf CaptureProxyX64.tar.gz`
9. Start the Capture Proxy WIP:
   1. Access the Capture Proxy shell script directory `cd trafficCaptureProxyServer/bin`
   2. `nohup ./trafficCaptureProxyServer --kafkaConnection <KAFKA_BROKERS> --destinationUri http://localhost:19200 --listenPort 9200 --enableMSKAuth --insecureDestination &`
      * This command will start the Capture Proxy in the background and allow it to continue past the lifetime of the shell session. 
      * **Warning**: If the machine running the elasticsearch/opensearch process is restarted, the Capture Proxy will need to be started again

    * Explanation of parameters in the command above:
    
    * --kafkaConnection: your MSK client endpoint.
      * --destinationUri: URI of the server that the Capture Proxy is capturing traffic for.
      * --listenPort: Exposed port for clients to connect to this proxy. (The original port that the node was listening to)
      * --enableMSKAuth: Enables SASL Kafka properties required for connecting to MSK with IAM auth.
      * --insecureDestination: Do not check the destination server’s certificate.

10. Test that the original opensearch/elasticsearch port for the node is available again.
    * `curl https://localhost:9200` or if security is not enabled `curl http://localhost:9200`
    * You should expect the same response when sending a request to either ports (`9200`, `19200`), with the difference being that requests that are sent to the `9200` port now pass through the Capture Proxy whose first priority is to forward the request to the opensearch/elasticsearch process, and whose second priority is to send the captured requests to Kafka.
11. Verify requests are sent to Kafka
    * Verify that a new topic has been created
        1. Log in to the Migration Console container.
        2. Go the Kafka tools directory
           cd kafka-tools/kafka/bin
        3. Run the following command to list the Kafka topics, and confirm that a new topic was created.
           `./kafka-topics.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --list --command-config ../../aws/msk-iam-auth.properties`
