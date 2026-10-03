import { check } from 'k6';
import http from '../lib/http-client.js';
import { CFG } from '../lib/config.js';
import { mapping } from '../lib/data/nyc_taxis/documents.js';

const PROXY_URL = CFG.CAPTURE_PROXY_URL || 'https://capture-proxy:9201';
const INDEX = CFG.INDEX_NAME || 'nyc_taxis';

export const options = {
  insecureSkipTLSVerify: true,
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const url = `${PROXY_URL}/${INDEX}`;
  const existing = http.get(url, { tags: { name: 'setup_check_index' } });
  check(existing, { 'index is absent or ready': (r) => r.status === 404 || r.status === 200 });
  if (existing.status !== 404) return;

  const created = http.put(url, mapping, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'setup_create_index' },
  });
  check(created, { 'index created (200)': (r) => r.status === 200 });
}
