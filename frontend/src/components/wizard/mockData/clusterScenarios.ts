import { ClusterInfo } from '@/generated/api';

// Debug scenario mock data for clusters
export const CLUSTER_SCENARIOS: Record<string, ClusterInfo> = {
  noAuth: {
    endpoint: 'https://example-cluster-no-auth.com:9200',
    protocol: 'https',
    enable_tls_verification: false,
    auth: {
      type: 'NO_AUTH'
    },
    version_override: null
  },
  basicAuth: {
    endpoint: 'https://example-cluster-basic-auth.com:9200',
    protocol: 'https',
    enable_tls_verification: true,
    auth: {
      type: 'BASIC_AUTH',
      username: 'admin',
      password: 'password123'
    },
    version_override: null
  },
  sigV4Auth: {
    endpoint: 'https://example-cluster-sigv4.us-east-1.es.amazonaws.com',
    protocol: 'https',
    enable_tls_verification: true,
    auth: {
      type: 'SIGV4',
      region: 'us-east-1',
      service: 'es'
    },
    version_override: null
  },
  basicAuthArn: {
    endpoint: 'https://example-cluster-basic-auth-arn.com:9200',
    protocol: 'https',
    enable_tls_verification: true,
    auth: {
      type: 'BASIC_AUTH_ARN',
      user_secret_arn: 'arn:aws:secretsmanager:us-east-1:123456789012:secret:example-basic-auth-secret-abc123'
    },
    version_override: null
  },
  withVersionOverride: {
    endpoint: 'https://example-cluster-with-version.com:9200',
    protocol: 'https',
    enable_tls_verification: true,
    auth: {
      type: 'NO_AUTH'
    },
    version_override: 'OpenSearch 2.11.0'
  },
  incomplete: {
    endpoint: '',
    protocol: 'https',
    enable_tls_verification: false,
    auth: {
      type: 'NO_AUTH'
    },
    version_override: null
  }
};
