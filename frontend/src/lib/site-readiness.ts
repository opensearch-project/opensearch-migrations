const CACHE_KEY = "siteReady";
const CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

export function getSiteReadiness(): boolean {
  const cached = localStorage.getItem(CACHE_KEY);
  if (!cached) return false;

  const { timestamp, ready } = JSON.parse(cached);
  return ready && Date.now() - timestamp < CACHE_TTL_MS;
}

export function setSiteReadiness(value: boolean) {
  localStorage.setItem(
    CACHE_KEY,
    JSON.stringify({ ready: value, timestamp: Date.now() }),
  );
}
