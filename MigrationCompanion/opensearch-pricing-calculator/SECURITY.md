# Security Policy

## Reporting a Vulnerability

If you discover a potential security issue in this project we ask that you notify OpenSearch Security directly via email to [security@opensearch.org](mailto:security@opensearch.org). Please do **not** create a public GitHub issue.

## Supported Versions

We recommend users stay up to date with the latest release to benefit from security fixes.

## Security Best Practices

When deploying this service:

- **Authentication**: This service does not implement authentication directly. Deploy behind an authenticating reverse proxy (e.g., API Gateway with IAM, ALB with OIDC) for production use.
- **TLS**: Always use HTTPS in production. The service listens on HTTP; terminate TLS at the load balancer or proxy.
- **Network**: Restrict access to the service using security groups or network policies. Do not expose directly to the public internet without an authenticating proxy.
- **Environment Variables**: Never commit secrets or AWS credentials. Use IAM roles for service authentication.
- **Rate Limiting**: The service includes built-in rate limiting, but consider additional protections at the infrastructure level for production deployments.
