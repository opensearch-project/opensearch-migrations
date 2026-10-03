// SPDX-License-Identifier: Apache-2.0

import { createSigV4Signer } from '../lib/sigv4.js';

const SIGNING_DATE = new Date('2000-01-01T00:00:00Z');
const EMPTY_BODY_HASH = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: expected '${expected}', got '${actual}'`);
  }
}

function sign(overrides = {}, credentials = {}) {
  const signer = createSigV4Signer({
    accessKeyId: 'foo',
    secretAccessKey: 'bar',
    region: 'us-bar-1',
    service: 'foo',
    endpoint: 'https://foo.us-bar-1.amazonaws.com',
    ...credentials,
  });
  return signer.sign({
    method: 'POST',
    path: '/',
    body: '',
    signingDate: SIGNING_DATE,
    ...overrides,
  });
}

export default function () {
  const empty = sign();
  assertEqual(empty.host, 'foo.us-bar-1.amazonaws.com', 'source host');
  assertEqual(empty['x-amz-date'], '20000101T000000Z', 'signing date');
  assertEqual(empty['x-amz-content-sha256'], EMPTY_BODY_HASH, 'empty payload hash');
  assertEqual(
    empty.authorization,
    'AWS4-HMAC-SHA256 Credential=foo/20000101/us-bar-1/foo/aws4_request, ' +
      'SignedHeaders=host;x-amz-content-sha256;x-amz-date, ' +
      'Signature=1e3b24fcfd7655c0c245d99ba7b6b5ca6174eab903ebfbda09ce457af062ad30',
    'empty request signature',
  );

  const body = sign({ body: 'It was the best of times, it was the worst of times' });
  assertEqual(
    body.authorization,
    'AWS4-HMAC-SHA256 Credential=foo/20000101/us-bar-1/foo/aws4_request, ' +
      'SignedHeaders=host;x-amz-content-sha256;x-amz-date, ' +
      'Signature=cf22a0befff359388f136b158f0b1b43db7b18d2ca65ce4112bc88a16815c4b6',
    'string payload signature',
  );

  const token = sign({}, { sessionToken: 'baz' });
  assertEqual(token['x-amz-security-token'], 'baz', 'session token');
  assertEqual(
    token.authorization,
    'AWS4-HMAC-SHA256 Credential=foo/20000101/us-bar-1/foo/aws4_request, ' +
      'SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, ' +
      'Signature=4fd09a8cf3b28a62a9c6c424f03ababcd703528578bc6ec9184fc585f18c3fbb',
    'session-token signature',
  );

  const query = sign({
    path: '/_search',
    query: { z: ['two', 'one'], 'a b': 'x/y' },
    body: '{}',
  });
  assertEqual(
    query.authorization,
    'AWS4-HMAC-SHA256 Credential=foo/20000101/us-bar-1/foo/aws4_request, ' +
      'SignedHeaders=host;x-amz-content-sha256;x-amz-date, ' +
      'Signature=700d89da5644837666f74b7545f6a081e1b36e641ed364c722eeea5ef06fd9ea',
    'sorted query signature',
  );

  const encodedPath = sign({ path: '/foo%3Dbar' });
  const normalizedPath = sign({ path: '/abc/../foo%3Dbar' });
  assertEqual(
    encodedPath.authorization,
    'AWS4-HMAC-SHA256 Credential=foo/20000101/us-bar-1/foo/aws4_request, ' +
      'SignedHeaders=host;x-amz-content-sha256;x-amz-date, ' +
      'Signature=fb4948cab44a9c47ce3b1a2489d01ec939fea9e79eccdb4593c11a94f207e075',
    'encoded path signature',
  );
  assertEqual(
    normalizedPath.authorization,
    encodedPath.authorization,
    'normalized path signature',
  );
}
