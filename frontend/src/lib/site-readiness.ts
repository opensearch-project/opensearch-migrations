"use client";

const CACHE_KEY = "siteReady";
const CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

export function getSiteReadiness(): boolean {
  if (typeof localStorage == undefined) {
    return false;
  }
  const cached = localStorage.getItem(CACHE_KEY);
  if (!cached) return false;

  const { timestamp, ready } = JSON.parse(cached);
  return ready && Date.now() - timestamp < CACHE_TTL_MS;
}

export function setSiteReadiness(value: boolean) {
  if (typeof localStorage == undefined) {
    return;
  }
  localStorage.setItem(
    CACHE_KEY,
    JSON.stringify({ ready: value, timestamp: Date.now() }),
  );
}
