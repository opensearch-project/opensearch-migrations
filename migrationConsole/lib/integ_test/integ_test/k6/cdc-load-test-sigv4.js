import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { Endpoint, SignatureV4 } from 'https://jslib.k6.io/aws/0.13.0/aws.js';

const creds = JSON.parse(open(__ENV.AWS_CREDS_FILE || '/tmp/aws-creds.json'));

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
const SOURCE_HOST = __ENV.SOURCE_HOST;
const INDEX = __ENV.INDEX_NAME || 'cdc-load-test';
const REGION = __ENV.AWS_REGION || 'us-east-1';

const signer = new SignatureV4({
  service: 'es',
  region: REGION,
  credentials: {
    accessKeyId: creds.accessKeyId,
    secretAccessKey: creds.secretAccessKey,
    sessionToken: creds.sessionToken,
  },
});

export default function () {
  const docId = `${__VU}-${__ITER}`;
  const doc = JSON.stringify({
    title: `doc-${docId}`,
    content: 'Load test document for CDC pipeline validation',
    timestamp: new Date().toISOString(),
  });

  const path = `/${INDEX}/_doc/${docId}`;

  // Sign for the source cluster hostname, send to the proxy endpoint.
  // The proxy forwards the request with the source-signed headers intact.
  const signed = signer.sign({
    method: 'PUT',
    endpoint: new Endpoint(`https://${SOURCE_HOST}`),
    path: path,
    headers: {
      'Content-Type': 'application/json',
      'host': SOURCE_HOST,
    },
    body: doc,
  });

  const res = http.put(
    `${PROXY}${path}`,
    doc,
    { headers: signed.headers }
  );

  proxyTTFB.add(res.timings.waiting);
  proxyReceiving.add(res.timings.receiving);

  check(res, {
    'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
  });
}
