export const PINNED_CURL_STORAGE_KEY =
  "workflow-manage-cluster-curl:pinned:v1";
export const CURL_WORKSPACE_STORAGE_KEY =
  "workflow-manage-cluster-curl:workspace:v1";
export const PINNED_RUNTIME_STATUS_STORAGE_KEY =
  "workflow-manage-runtime-status:pinned:v1";
export const RUNTIME_STATUS_WORKSPACE_STORAGE_KEY =
  "workflow-manage-runtime-status:workspace:v1";
export const RUNTIME_DASHBOARD_CHANGE_EVENT =
  "workflow-manage-runtime-dashboard-change";


export interface RuntimeDashboardSnapshot {
  version: 1;
  curlExplorers: unknown[];
  runtimeStatuses: unknown[];
}


function storedArray(key: string): unknown[] {
  try {
    const value: unknown = JSON.parse(
      globalThis.localStorage?.getItem(key) ?? "[]",
    );
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}


function encodeBase64Url(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return globalThis.btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}


function decodeBase64Url(value: string): string {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized.padEnd(
    normalized.length + (4 - normalized.length % 4) % 4,
    "=",
  );
  const binary = globalThis.atob(padded);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}


function currentSnapshot(): RuntimeDashboardSnapshot {
  return {
    version: 1,
    curlExplorers: storedArray(CURL_WORKSPACE_STORAGE_KEY),
    runtimeStatuses: storedArray(RUNTIME_STATUS_WORKSPACE_STORAGE_KEY),
  };
}


export function runtimeDashboardUrlFor(
  snapshot: RuntimeDashboardSnapshot,
): string {
  const url = new URL("/runtime-dashboard", globalThis.location.origin);
  const encoded = encodeBase64Url(JSON.stringify(snapshot));
  url.hash = `v1=${encoded}`;
  return url.toString();
}


export function runtimeDashboardUrl(): string {
  return runtimeDashboardUrlFor(currentSnapshot());
}


export function openRuntimeDashboard(): void {
  globalThis.open(
    runtimeDashboardUrl(),
    "_blank",
    "noopener,noreferrer",
  );
}


export function openRuntimeDashboardSnapshot(
  snapshot: RuntimeDashboardSnapshot,
): void {
  globalThis.open(
    runtimeDashboardUrlFor(snapshot),
    "_blank",
    "noopener,noreferrer",
  );
}


export async function copyRuntimeDashboardUrl(): Promise<void> {
  try {
    await globalThis.navigator.clipboard?.writeText(runtimeDashboardUrl());
  } catch {
    // The address bar still contains the complete restorable dashboard URL.
  }
}


export function hydrateRuntimeDashboardFromHash(): void {
  const match = /^#v1=(.+)$/.exec(globalThis.location.hash);
  if (!match) return;
  try {
    const snapshot = JSON.parse(
      decodeBase64Url(match[1]),
    ) as Partial<RuntimeDashboardSnapshot>;
    if (snapshot.version !== 1) return;
    if (Array.isArray(snapshot.curlExplorers)) {
      globalThis.localStorage?.setItem(
        CURL_WORKSPACE_STORAGE_KEY,
        JSON.stringify(snapshot.curlExplorers),
      );
    }
    if (Array.isArray(snapshot.runtimeStatuses)) {
      globalThis.localStorage?.setItem(
        RUNTIME_STATUS_WORKSPACE_STORAGE_KEY,
        JSON.stringify(snapshot.runtimeStatuses),
      );
      globalThis.localStorage?.setItem(
        PINNED_RUNTIME_STATUS_STORAGE_KEY,
        JSON.stringify(snapshot.runtimeStatuses.filter((value) => (
          typeof value === "object"
          && value !== null
          && "pinned" in value
          && value.pinned === true
        ))),
      );
    }
  } catch {
    // An invalid fragment falls back to the browser's saved workspace.
  }
}


export function notifyRuntimeDashboardChanged(): void {
  globalThis.dispatchEvent(new Event(RUNTIME_DASHBOARD_CHANGE_EVENT));
}


export function syncRuntimeDashboardUrl(): void {
  const url = new URL(runtimeDashboardUrl());
  globalThis.history.replaceState(
    globalThis.history.state,
    "",
    `${url.pathname}${url.search}${url.hash}`,
  );
}
