import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

const proxyTTFB = new Trend('proxy_ttfb', true);
const proxyReceiving = new Trend('proxy_receiving', true);

export const options = {
  scenarios: {
    cdc_writes: {
      executor: 'constant-arrival-rate',
      rate: __ENV.WRITE_RATE ? parseInt(__ENV.WRITE_RATE) : 50,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    http_req_duration: ['p(95)<2000'],
  },
  insecureSkipTLSVerify: true,
};

const PROXY = __ENV.PROXY_ENDPOINT;
const INDEX = __ENV.INDEX_NAME || 'cdc-load-test';
const USERNAME = __ENV.USERNAME || '';
const PASSWORD = __ENV.PASSWORD || '';
const DOC_SIZE = __ENV.DOC_SIZE || 'small';

const headers = { 'Content-Type': 'application/json' };
if (USERNAME) {
  headers['Authorization'] = `Basic ${encoding.b64encode(`${USERNAME}:${PASSWORD}`)}`;
}

function makeDoc(id) {
  const base = {
    title: `doc-${id}`,
    timestamp: new Date().toISOString(),
  };
  if (DOC_SIZE === 'large') {
    base.content = 'x'.repeat(9000);
  } else if (DOC_SIZE === 'xlarge') {
    base.content = 'x'.repeat(95000);
  } else {
    base.content = 'Load test document for CDC pipeline validation';
  }
  return JSON.stringify(base);
}

export default function () {
  const docId = `${__VU}-${__ITER}`;
  const doc = makeDoc(docId);

  const res = http.put(
    `${PROXY}/${INDEX}/_doc/${docId}`,
    doc,
    { headers }
  );

  proxyTTFB.add(res.timings.waiting);
  proxyReceiving.add(res.timings.receiving);

  check(res, {
    'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
  });
}
