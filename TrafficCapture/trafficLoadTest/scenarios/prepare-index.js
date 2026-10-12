import { check } from 'k6';
import http from '../lib/http-client.js';
import { CFG } from '../lib/config.js';
import { mapping } from '../lib/data/nyc_taxis/documents.js';

const PROXY_URL = CFG.CAPTURE_PROXY_URL || 'https://capture-proxy:9201';
const INDEX = CFG.INDEX_NAME || 'nyc_taxis';
const INDEX_NUMBER_OF_SHARDS_TEXT = CFG.INDEX_NUMBER_OF_SHARDS || '1';

if (!/^[1-9][0-9]*$/.test(INDEX_NUMBER_OF_SHARDS_TEXT)) {
  throw new Error(
    `INDEX_NUMBER_OF_SHARDS must be a positive integer, got '${INDEX_NUMBER_OF_SHARDS_TEXT}'`
  );
}

const INDEX_NUMBER_OF_SHARDS = Number(INDEX_NUMBER_OF_SHARDS_TEXT);
if (!Number.isSafeInteger(INDEX_NUMBER_OF_SHARDS)) {
  throw new Error(
    `INDEX_NUMBER_OF_SHARDS is outside JavaScript's safe integer range: ` +
    `'${INDEX_NUMBER_OF_SHARDS_TEXT}'`
  );
}

const mappingDefinition = JSON.parse(mapping);
const indexDefinition = JSON.stringify({
  ...mappingDefinition,
  settings: {
    ...(mappingDefinition.settings || {}),
    number_of_shards: INDEX_NUMBER_OF_SHARDS,
  },
});

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

  const created = http.put(url, indexDefinition, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'setup_create_index' },
  });
  check(created, { 'index created (200)': (r) => r.status === 200 });
}
