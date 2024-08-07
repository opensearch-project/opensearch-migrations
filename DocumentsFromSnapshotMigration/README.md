# Documents from Snapshot Migration (RFS)


## Arguments
| Argument                          | Description                                                                                                                                             |
|-----------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------|
| --snapshot-name                   | The name of the snapshot to migrate                                                                                                                     |
| --snapshot-local-dir              | The absolute path to the directory on local disk where the snapshot exists                                                                              |
| --s3-local-dir                    | The absolute path to the directory on local disk to download S3 files to                                                                                |
| --s3-repo-uri                     | The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2                                                                                         |
| --s3-region                       | The AWS Region the S3 bucket is in, like: us-east-2                                                                                                     |
| --lucene-dir                      | The absolute path to the directory where we'll put the Lucene docs                                                                                      |
| --index-allowlist                 | The list of index names to migrate (e.g. 'logs_2024_01, logs_2024_02')                                                                                  |
| --max-shard-size-bytes            | The maximum shard size in bytes to allow when performing the document migration                                                                         |
| --max-initial-lease-duration      | The maximum time for the first attempt to migrate a shard's documents                                                                                   |
| --otel-collector-endpoint         | The endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be forwarded                                                      |
| --target-host                     | The target host and port (e.g. http://localhost:9200)                                                                                                   |
| --target-username                 | The username for target cluster authentication                                                                                                          |
| --target-password                 | The password for target cluster authentication                                                                                                          |
| --target-aws-region               | The AWS region for the target cluster. Required if using SigV4 authentication                                                                           |
| --target-aws-service-signing-name | The AWS service signing name (e.g. 'es' for Amazon OpenSearch Service, 'aoss' for Amazon OpenSearch Serverless). Required if using SigV4 authentication |
| --target-insecure                 | Flag to allow untrusted SSL certificates for target cluster                                                                                             |
