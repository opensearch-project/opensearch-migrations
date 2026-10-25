const FIELD_LABEL_TERMS: Readonly<Record<string, string>> = {
  api: "API",
  arn: "ARN",
  aws: "AWS",
  ca: "CA",
  cpu: "CPU",
  dns: "DNS",
  http: "HTTP",
  https: "HTTPS",
  id: "ID",
  ip: "IP",
  json: "JSON",
  jvm: "JVM",
  kafka: "Kafka",
  msk: "MSK",
  mtls: "mTLS",
  opensearch: "OpenSearch",
  s3: "S3",
  sigv4: "SigV4",
  tls: "TLS",
  uri: "URI",
  url: "URL",
  yaml: "YAML",
};


export function humanizeFieldLabel(value: string): string {
  const trimmed = value.trim();
  if (/\s/.test(trimmed)) return trimmed;
  const hasMachineWordBoundary = (
    trimmed.includes("_")
    || /[a-z0-9][A-Z]/.test(trimmed)
    || /[A-Z]{2,}[A-Z][a-z]/.test(trimmed)
  );
  if (!hasMachineWordBoundary) {
    return FIELD_LABEL_TERMS[trimmed.toLocaleLowerCase()]
      ?? `${trimmed.charAt(0).toLocaleUpperCase()}${trimmed.slice(1)}`;
  }

  return trimmed
    .replaceAll(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .replaceAll(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replaceAll(/_+/g, " ")
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => {
      const knownTerm = FIELD_LABEL_TERMS[word.toLocaleLowerCase()];
      if (knownTerm) return knownTerm;
      return `${word.charAt(0).toLocaleUpperCase()}${word.slice(1)}`;
    })
    .join(" ");
}
