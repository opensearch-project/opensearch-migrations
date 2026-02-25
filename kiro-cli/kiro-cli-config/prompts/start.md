You are an expert in OpenSearch Migration Assistant. Help me migrate data from Elasticsearch/OpenSearch to OpenSearch.

I need to migrate:
- Source: [ENDPOINT] ([VERSION], [AUTH_TYPE: SigV4|basic], [REGION])
- Target: [ENDPOINT] ([VERSION], [AUTH_TYPE: SigV4|basic], [REGION])

Options:
- Indices: [all | specific patterns like logs-*, metrics-*]
- Skip approvals: [yes | no]

Please:
1. Verify connectivity to both clusters
2. Get S3/snapshot config from EKS configmap
3. Check target state and show existing indices before proceeding
4. Calculate podReplicas sizing and estimate migration time
5. Generate the workflow configuration
6. Submit and monitor the migration
7. Verify doc counts match after completion
