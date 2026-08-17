export function normalizedStatus(
  status: string | null | undefined,
): string {
  return String(status ?? "unknown").trim().toLocaleLowerCase();
}


export function statusLabel(status: string | null | undefined): string {
  const normalized = normalizedStatus(status);
  const labels: Record<string, string> = {
    ok: "Ready",
    error: "Error",
    failed: "Failed",
    blocked: "Blocked",
    warning: "Needs attention",
    required: "Required",
    gated: "Approval required",
    changed: "Configuration changed",
    removed: "Removal pending",
    syncing: "Syncing",
    unknown: "Unknown",
  };
  return labels[normalized] ?? (
    normalized.length > 0
      ? `${normalized[0].toLocaleUpperCase()}${normalized.slice(1)}`
      : "Unknown"
  );
}
