import type { SelectProps } from "@cloudscape-design/components";

export const ELASTICSEARCH_VERSIONS: SelectProps.Option[] = [
  { label: "ES 5.6.16", value: "ES 5.6.16" },
  { label: "ES 6.8.23", value: "ES 6.8.23" },
  { label: "ES 7.10.2", value: "ES 7.10.2" },
  { label: "ES 7.17.0", value: "ES 7.17.0" },
  { label: "ES 8.11.0", value: "ES 8.11.0" },
  { label: "ES 8.12.0", value: "ES 8.12.0" },
];

export const OPENSEARCH_VERSIONS: SelectProps.Option[] = [
  { label: "OS 1.3.14", value: "OS 1.3.14" },
  { label: "OS 2.11.0", value: "OS 2.11.0" },
  { label: "OS 2.13.0", value: "OS 2.13.0" },
  { label: "OS 2.15.0", value: "OS 2.15.0" },
  { label: "OS 3.0.0", value: "OS 3.0.0" },
];

export const ALL_VERSIONS: SelectProps.Option[] = [
  { label: "Elasticsearch", value: "", disabled: true },
  ...ELASTICSEARCH_VERSIONS,
  { label: "OpenSearch", value: "", disabled: true },
  ...OPENSEARCH_VERSIONS,
];

export const AWS_REGIONS: SelectProps.Option[] = [
  { label: "US East (N. Virginia) - us-east-1", value: "us-east-1" },
  { label: "US East (Ohio) - us-east-2", value: "us-east-2" },
  { label: "US West (N. California) - us-west-1", value: "us-west-1" },
  { label: "US West (Oregon) - us-west-2", value: "us-west-2" },
  { label: "Europe (Ireland) - eu-west-1", value: "eu-west-1" },
  { label: "Europe (London) - eu-west-2", value: "eu-west-2" },
  { label: "Europe (Frankfurt) - eu-central-1", value: "eu-central-1" },
  { label: "Asia Pacific (Tokyo) - ap-northeast-1", value: "ap-northeast-1" },
  { label: "Asia Pacific (Seoul) - ap-northeast-2", value: "ap-northeast-2" },
  { label: "Asia Pacific (Singapore) - ap-southeast-1", value: "ap-southeast-1" },
  { label: "Asia Pacific (Sydney) - ap-southeast-2", value: "ap-southeast-2" },
  { label: "Asia Pacific (Mumbai) - ap-south-1", value: "ap-south-1" },
  { label: "South America (São Paulo) - sa-east-1", value: "sa-east-1" },
  { label: "Canada (Central) - ca-central-1", value: "ca-central-1" },
];

export const AUTH_TYPES: SelectProps.Option[] = [
  { label: "None", value: "none", description: "No authentication" },
  { label: "Basic Auth", value: "basic", description: "Username/password via K8s secret" },
  { label: "AWS SigV4", value: "sigv4", description: "AWS IAM authentication" },
];

export const SIGV4_SERVICES: SelectProps.Option[] = [
  { label: "Amazon OpenSearch Service (es)", value: "es" },
  { label: "Amazon OpenSearch Serverless (aoss)", value: "aoss" },
];

// Shard size presets in bytes
const GB = 1024 * 1024 * 1024;
export const SHARD_SIZE_PRESETS: SelectProps.Option[] = [
  { label: "10 GB", value: String(10 * GB), description: "Small shards" },
  { label: "25 GB", value: String(25 * GB), description: "Medium shards" },
  { label: "40 GB", value: String(40 * GB), description: "Recommended for most cases" },
  { label: "50 GB", value: String(50 * GB), description: "Large shards" },
  { label: "80 GB (Default)", value: String(80 * GB), description: "Maximum recommended size" },
  { label: "Custom", value: "custom", description: "Enter a custom value" },
];

export const formatBytes = (bytes: number): string => {
  if (bytes === 0) return "0 Bytes";
  const k = 1024;
  const sizes = ["Bytes", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
};

export const parseGBToBytes = (gb: number): number => gb * GB;
