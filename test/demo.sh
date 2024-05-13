#!/bin/bash

overall_setup() {
	# This should be run before any other steps--it deploys the solution and sets it to a
	# starting state without the capture proxy running
	:<<EOF
	- Set AWS credentials
	- Set AWS_DEFAULT_REGION
	- Set STAGE
EOF

	./awsE2ESolutionSetup.sh --stage ${STAGE}

	# Turn off caputre proxy and reset elasticsearch port
	:<<RUN_ON_EC2_NODE
	bash
	sudo su
	kill $(pgrep -f "trafficCaptureProxyServer") && \
	sed -i 's/http.port: [0-9]\+/http.port: 9200/' /home/ec2-user/elasticsearch/config/elasticsearch.yml && \
	kill $(pgrep -f "elasticsearch") && \
	sudo -u ec2-user nohup /home/ec2-user/elasticsearch/bin/elasticsearch >/dev/null 2>&1 &

	# To verify
	echo "es processes:" && pgrep -f "elasticsearch" && echo "proxy processes (should be empty):" && pgrep -f "trafficCaptureProxyServer" || curl localhost:9200
RUN_ON_EC2_NODE

}

cleanup() {
	# These commands are necessary if the environment may not be clean, but shouldn't be required otherwise.

	# Turn off the replayer
	aws ecs update-service --cluster migration-${STAGE}-ecs-cluster --service migration-${STAGE}-traffic-replayer-default --desired-count 0

	# Check that replayer args in the JSON task definition include `--speedup-factor 5.0 --remove-auth-header` -- if not modify and redeploy replayer

	# Turn off RFS
	aws ecs update-service --cluster migration-${STAGE}-ecs-cluster --service migration-${STAGE}-reindex-from-snapshot --desired-count 0

	# Get kafka brokers, source_url, and console task arn
	MIGRATION_KAFKA_BROKERS=$(aws ssm get-parameter --name "/migration/$STAGE/default/kafkaBrokers" --query 'Parameter.Value' --output text | tr ',' '@')
	SOURCE_URL="http://$(aws cloudformation describe-stacks --stack-name opensearch-infra-stack-Migration-Source --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text):9200"
	CONSOLE_TASK_ARN=$(aws ecs list-tasks --cluster migration-${STAGE}-ecs-cluster --family "migration-${STAGE}-migration-console" | jq --raw-output '.taskArns[0]')

	# Delete the Kafka topic
	aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${CONSOLE_TASK_ARN}" --container "migration-console" --interactive --command "./kafka-tools/kafka/bin/kafka-topics.sh --bootstrap-server $MIGRATION_KAFKA_BROKERS --delete --topic logging-traffic-topic --command-config kafka-tools/aws/msk-iam-auth.properties"

	# Verify that "logging-traffic-topic" does not appear in output from:
	aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${CONSOLE_TASK_ARN}" --container "migration-console" --interactive --command "./kafka-tools/kafka/bin/kafka-topics.sh --bootstrap-server $MIGRATION_KAFKA_BROKERS --list --command-config kafka-tools/aws/msk-iam-auth.properties"

	# Recreate the kafka topic
	aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${CONSOLE_TASK_ARN}" --container "migration-console" --interactive --command "./kafka-tools/kafka/bin/kafka-topics.sh --bootstrap-server $MIGRATION_KAFKA_BROKERS --create --topic logging-traffic-topic --command-config kafka-tools/aws/msk-iam-auth.properties"

	./accessContainer.sh migration-console $STAGE $AWS_DEFAULT_REGION
	:<<RUN_ON_MIGRATION_CONSOLE

	# Delete all data from both clusters
	curl -XDELETE "$MIGRATION_DOMAIN_ENDPOINT/*,-.*,-searchguard*,-sg7*" --insecure && curl -XDELETE "$SOURCE_URL/*,-.*,-searchguard*,-sg7*" --insecure

	# Delte the RFS snapshot
	curl -X DELETE $SOURCE_URL/_snapshot/migration_assistant_repo/rfs-snapshot

	# Delete tuples
	rm -rf /shared-replayer-output/traffic-replayer-default/*
RUN_ON_MIGRATION_CONSOLE
}

predemo_setup() {
	# This step should be run after setup or cleanup, but before the demo. It seeds the starting data.
	:<<RUN_ON_MIGRATION_CONSOLE

	# Seed benchmark data
	opensearch-benchmark execute-test --distribution-version=1.0.0 --pipeline=benchmark-only --kill-running-processes --workload=geonames --workload-params "target_throughput:10,bulk_size:1000,bulk_indexing_clients:2,search_clients:1,ingest_percentage:10" --target-host=$SOURCE_URL

	opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$SOURCE_URL --workload=http_logs --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:10,bulk_size:100,bulk_indexing_clients:2,search_clients:1" &&
	opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=$SOURCE_URL --workload=nyc_taxis --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:10,bulk_size:100,bulk_indexing_clients:2,search_clients:1"
RUN_ON_MIGRATION_CONSOLE
}

demo() {
	./accessContainer.sh migration-console $STAGE $AWS_DEFAULT_REGION
	:<<RUN_ON_MIGRATION_CONSOLE
	# Show starting setup of data & indices
	./catIndices.sh --source-no-auth --target-no-auth --source-endpoint $SOURCE_URL
RUN_ON_MIGRATION_CONSOLE

	# Log onto EC2 node to start the capture proxy
	:<<RUN_ON_EC2_NODE
	bash
	sudo su
	cd /home/ec2-user
	./capture-proxy/startCaptureProxy.sh --stage demo --kafka-endpoints <paste_kafka_brokers>
RUN_ON_EC2_NODE

	# Cat indices again and show that data is being written to Kafka
	:<<RUN_ON_MIGRATION_CONSOLE
	# In a live demo, run with `watch -n`
	./catIndices.sh --source-no-auth --target-no-auth --source-endpoint $SOURCE_URL
	
	# Start document flow
	./simpleDocumentGenerator.py --endpoint $SOURCE_URL &> /dev/null

	# Show Kafka offsets
	./kafka-tools/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --topic logging-traffic-topic --time -1 --command-config kafka-tools/aws/msk-iam-auth.properties

	# Start an RFS migration
	aws ecs update-service --cluster migration-${MIGRATION_STAGE}-ecs-cluster --service migration-${MIGRATION_STAGE}-reindex-from-snapshot --desired-count 1

	# Check logs to find when it has finished
	# TODO: add code snippet here for pulling logs

	# Start replayer
	aws ecs update-service --cluster migration-${MIGRATION_STAGE}-ecs-cluster --service migration-${MIGRATION_STAGE}-traffic-replayer-default --desired-count 1

	# Show tuples
	./humanReadableLogs.py /shared-replayer-output/traffic-replayer-default/tuples.log

	head -n 1 /shared-replayer-output/traffic-replayer-default/readable-tuples.log | jq

RUN_ON_MIGRATION_CONSOLE
}

overall_setup()
predemo_setup()
demo()