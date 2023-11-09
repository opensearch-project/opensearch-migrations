# Capture Proxy

## How to attach a Capture Proxy on a coordinator node.

Follow documentation for [deploying solution](../../deployment/README.md). Then, on a cluster with at least two coordinator nodes, the user can attach a Capture Proxy on a node by following these steps:
Please note that this is one method for installing the Capture Proxy on a node, and that these steps may vary depending on your environment.


These are the **prerequisites** to being able to attach the Capture Proxy:

* **Make sure that the node and your MSK client are in the same VPC and Security Groups**
    * Add the following IAM policy to the node/EC2 instance so that it’s able to store the captured traffic in Kafka:
    * From the AWS Console, go to the EC2 instance page, click on **IAM Role**,  click on **Add permissions**, choose **Create inline policy**, click on **JSON VIEW** then add the following policy (replace region and account-id).

    ```json
    {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Action": "kafka-cluster:Connect",
                "Resource": "arn:aws:kafka:<region>:<account-id>:cluster/migration-msk-cluster-<stage>/*",
                "Effect": "Allow"
            },
            {
                "Action": [
                    "kafka-cluster:CreateTopic",
                    "kafka-cluster:DescribeTopic",
                    "kafka-cluster:WriteData"
                ],
                "Resource": "arn:aws:kafka:<region>:<account-id>:topic/migration-msk-cluster-<stage>/*",
                "Effect": "Allow"
            }
        ]
    }
  ```

* From **linux command line** of that EC2 instance: Check that the **JAVA_HOME environment variable is set** properly `(echo $JAVA_HOME)`, if not, then try running the following command that might help set it correctly:

  `JAVA_HOME=$(dirname "$(dirname "$(type -p java)")")`
    * If that doesn’t work, then find the java directory on your node and set it as $JAVA_HOME

### Follow these steps to attach a Capture Proxy on the node.

1. **Log in to one of the coordinator nodes** for command line access.
2. **Update node’s port setting**.
    1. **Update elasticsearch.yml/opensearch.yml**. Add this line to the node’s config file:
       http.port: 19200
3. **Restart Elasticsearch/OpenSearch process** so that the process will bind to the newly configured port. For example, if systemctl is available on your linux distribution you can run the following (Note: depending on your installation of Elasticsearch, these methods may not work for you)
   1. `sudo systemctl restart elasticsearch.service`
   
4. **Verify process is bound to new port**. Run netstat -tapn to see if the new port is being listened on.
   If the new port is not there, then there is a chance that Elasticsearch/ OpenSearch is not running, in that case, you must start the process again. (Depending on your setup, restarting/starting the Elasticsearch process may differ)
5. **Test the new port** by sending any kind of traffic or request, e.g; curl https://localhost:19200 or http://
6. **Download Capture Proxy**:
    1. Go to the Opensearch Migrations latest releases page: https://github.com/opensearch-project/opensearch-migrations/releases/latest
    2. Copy the link for the Capture Proxy tar file, mind your instance’s architecture.
    3. `curl -L0 <capture-proxy-tar-file-link> --output CaptureProxyX64.tar.gz`
    4.  Unpack solution tarball: `tar -xvf CaptureProxyX64.tar.gz`
    5. `cd CaptureProxyX64/bin`
7. **Running the Capture Proxy**:
        1. `nohup ./CaptureProxyX64 --kafkaConnection <msk-endpoint> --destinationUri http://localhost:19200 —listenPort 9200 —enableMSKAuth --insecureDestination &`

    **Explanation of parameters** in the command above:
    
    * **--kafkaConnection**: your MSK client endpoint.
    * **--destinationUri**: URI of the server that the Capture Proxy is capturing traffic for.
    * **--listenPort**: Exposed port for clients to connect to this proxy. (The original port that the node was listening to)
    * **--enableMSKAuth**: Enables SASL Kafka properties required for connecting to MSK with IAM auth.
    * **--insecureDestination**: Do not check the destination server’s certificate.



8. **Test the port** that the Capture Proxy is now listening to.
    1. `curl https://localhost:9200` or `http://`
    2. You should expect the same response when sending a request to either ports (9200, 19200), except that the traffic sent to the port that the Capture Proxy is listening to, will be captured and sent to your MSK Client, also forwarded to the new Elasticsearch port.
9. **Verify requests are sent to Kafka**
   * **Verify that a new topic has been created**
     1. Log in to the Migration Console container.
     2. Go the Kafka tools directory
       cd kafka-tools/kafka/bin
     3. Create the following file to allow communication with an AWS MSK cluster. Name it: `msk-iam-auth.properties`

     ```
     # --- Additional setup to use AWS MSK IAM library for communication with an AWS MSK cluster
     # Sets up TLS for encryption and SASL for authN.
     security.protocol = SASL_SSL
    
     # Identifies the SASL mechanism to use.
     sasl.mechanism = AWS_MSK_IAM
    
     # Binds SASL client implementation.
     sasl.jaas.config = software.amazon.msk.auth.iam.IAMLoginModule required;
    
     # Encapsulates constructing a SigV4 signature based on extracted credentials.
     # The SASL client bound by "sasl.jaas.config" invokes this class.
     sasl.client.callback.handler.class = software.amazon.msk.auth.iam.IAMClientCallbackHandler
     ```
       
     4.  Run the following command to list the Kafka topics, and confirm that a new topic was created.
         `./kafka-topics.sh —bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" —list —command-config msk-iam-auth.properties`
