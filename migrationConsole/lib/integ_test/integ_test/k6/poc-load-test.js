import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

const proxyTTFB = new Trend('proxy_ttfb', true);
const proxyReceiving = new Trend('proxy_receiving', true);

export const options = {
  insecureSkipTLSVerify: true,
  scenarios: {
    low_rate: {
      executor: 'constant-arrival-rate',
      rate: 10, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 10, maxVUs: 20,
      exec: 'indexSmallDoc',
      tags: { scenario: 'low_rate_10rps' },
      startTime: '0s',
    },
    medium_rate: {
      executor: 'constant-arrival-rate',
      rate: 50, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 50, maxVUs: 100,
      exec: 'indexSmallDoc',
      tags: { scenario: 'medium_rate_50rps' },
      startTime: '35s',
    },
    high_rate: {
      executor: 'constant-arrival-rate',
      rate: 200, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 100, maxVUs: 200,
      exec: 'indexSmallDoc',
      tags: { scenario: 'high_rate_200rps' },
      startTime: '70s',
    },
    large_docs: {
      executor: 'constant-arrival-rate',
      rate: 50, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 50, maxVUs: 100,
      exec: 'indexLargeDoc',
      tags: { scenario: 'large_docs_10kb' },
      startTime: '105s',
    },
    xlarge_docs: {
      executor: 'constant-arrival-rate',
      rate: 10, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 20, maxVUs: 40,
      exec: 'indexXLargeDoc',
      tags: { scenario: 'xlarge_docs_100kb' },
      startTime: '140s',
    },
  },
  thresholds: {
    'http_req_failed{scenario:low_rate_10rps}': ['rate==0'],
    'http_req_failed{scenario:medium_rate_50rps}': ['rate==0'],
    'http_req_failed{scenario:high_rate_200rps}': ['rate<0.01'],
    'http_req_failed{scenario:large_docs_10kb}': ['rate==0'],
    'http_req_failed{scenario:xlarge_docs_100kb}': ['rate==0'],
    'http_req_duration{scenario:low_rate_10rps}': ['p(95)<2000'],
    'http_req_duration{scenario:medium_rate_50rps}': ['p(95)<2000'],
    'http_req_duration{scenario:large_docs_10kb}': ['p(95)<3000'],
  },
};

const PROXY = __ENV.PROXY_ENDPOINT || 'https://capture-proxy:9201';
const USERNAME = __ENV.USERNAME || '';
const PASSWORD = __ENV.PASSWORD || '';

const headers = { 'Content-Type': 'application/json' };
if (USERNAME) {
  headers['Authorization'] = `Basic ${encoding.b64encode(`${USERNAME}:${PASSWORD}`)}`;
}

function sendDoc(index, docId, body) {
  const res = http.put(`${PROXY}/${index}/_doc/${docId}`, body, { headers });
  proxyTTFB.add(res.timings.waiting);
  proxyReceiving.add(res.timings.receiving);
  check(res, {
    'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
  });
}

export function indexSmallDoc() {
  const id = `${__VU}-${__ITER}`;
  sendDoc('poc-small', id, JSON.stringify({
    title: `doc-${id}`,
    content: 'Load test document for CDC pipeline validation',
    timestamp: new Date().toISOString(),
  }));
}

export function indexLargeDoc() {
  const id = `${__VU}-${__ITER}`;
  sendDoc('poc-large', id, JSON.stringify({
    title: `doc-${id}`,
    content: 'x'.repeat(9000),
    timestamp: new Date().toISOString(),
  }));
}

export function indexXLargeDoc() {
  const id = `${__VU}-${__ITER}`;
  sendDoc('poc-xlarge', id, JSON.stringify({
    title: `doc-${id}`,
    content: 'x'.repeat(95000),
    timestamp: new Date().toISOString(),
  }));
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(data.metrics, null, 2) + '\n',
    '/tmp/poc-results.json': JSON.stringify(data, null, 2),
  };
}
