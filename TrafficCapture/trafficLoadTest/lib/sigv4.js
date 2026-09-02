// SPDX-License-Identifier: Apache-2.0
// Repository-owned AWS Signature Version 4 request signing for stock k6.

import crypto from 'k6/crypto';

const ALGORITHM = 'AWS4-HMAC-SHA256';
const TERMINATOR = 'aws4_request';
const EMPTY_BODY_HASH = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

function awsEncode(value) {
  return encodeURIComponent(String(value)).replace(
    /[!'()*]/g,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}

function canonicalPath(path) {
  const value = path || '/';
  return value.split('/').map((segment) => awsEncode(segment)).join('/');
}

function canonicalQuery(query) {
  const pairs = [];
  for (const key of Object.keys(query || {})) {
    const values = Array.isArray(query[key]) ? query[key] : [query[key]];
    for (const value of values) {
      pairs.push([awsEncode(key), awsEncode(value == null ? '' : value)]);
    }
  }
  pairs.sort(([leftKey, leftValue], [rightKey, rightValue]) => {
    if (leftKey !== rightKey) return leftKey < rightKey ? -1 : 1;
    if (leftValue === rightValue) return 0;
    return leftValue < rightValue ? -1 : 1;
  });
  return pairs.map(([key, value]) => `${key}=${value}`).join('&');
}

function signingHost(endpoint) {
  const match = /^https?:\/\/([^/?#]+)(?:[/?#]|$)/i.exec(endpoint);
  if (!match || match[1].includes('@')) {
    throw new Error(`SIGV4_SIGNING_ENDPOINT must be an absolute HTTP(S) endpoint: '${endpoint}'`);
  }
  return match[1];
}

function signingDateParts(date) {
  const longDate = date.toISOString().replace(/[:-]|\.\d{3}/g, '');
  return { longDate, shortDate: longDate.slice(0, 8) };
}

function hashBody(body) {
  if (body == null || body === '') return EMPTY_BODY_HASH;
  if (typeof body === 'string' || body instanceof ArrayBuffer) {
    return crypto.sha256(body, 'hex').toLowerCase();
  }
  if (ArrayBuffer.isView(body)) {
    const bytes = body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength);
    return crypto.sha256(bytes, 'hex').toLowerCase();
  }
  throw new Error('SigV4 requests require a string or binary body');
}

function hmac(key, value, output = 'binary') {
  return crypto.hmac('sha256', key, value, output);
}

function deriveSigningKey(secretAccessKey, shortDate, region, service) {
  const dateKey = hmac(`AWS4${secretAccessKey}`, shortDate);
  const regionKey = hmac(dateKey, region);
  const serviceKey = hmac(regionKey, service);
  return hmac(serviceKey, TERMINATOR);
}

function normalizeHeaderValue(value) {
  return String(value).trim().replace(/\s+/g, ' ');
}

export function createSigV4Signer({
  accessKeyId,
  secretAccessKey,
  sessionToken,
  region,
  service = 'es',
  endpoint,
}) {
  if (!accessKeyId || !secretAccessKey) {
    throw new Error('SigV4 authentication requires AWS access-key credentials');
  }
  if (!region) throw new Error('SigV4 authentication requires AWS_REGION');
  if (!endpoint) throw new Error('SigV4 authentication requires SIGV4_SIGNING_ENDPOINT');

  const host = signingHost(endpoint);

  return {
    sign({ method, path, query, body, signingDate = new Date() }) {
      const { longDate, shortDate } = signingDateParts(signingDate);
      const payloadHash = hashBody(body);
      const headers = {
        host,
        'x-amz-content-sha256': payloadHash,
        'x-amz-date': longDate,
      };
      if (sessionToken) headers['x-amz-security-token'] = sessionToken;

      const signedHeaderNames = Object.keys(headers).sort();
      const canonicalHeaders = signedHeaderNames
        .map((name) => `${name}:${normalizeHeaderValue(headers[name])}\n`)
        .join('');
      const signedHeaders = signedHeaderNames.join(';');
      const canonicalRequest = [
        method.toUpperCase(),
        canonicalPath(path),
        canonicalQuery(query),
        canonicalHeaders,
        signedHeaders,
        payloadHash,
      ].join('\n');

      const scope = `${shortDate}/${region}/${service}/${TERMINATOR}`;
      const stringToSign = [
        ALGORITHM,
        longDate,
        scope,
        crypto.sha256(canonicalRequest, 'hex').toLowerCase(),
      ].join('\n');
      const signature = hmac(
        deriveSigningKey(secretAccessKey, shortDate, region, service),
        stringToSign,
        'hex',
      ).toLowerCase();

      headers.authorization = (
        `${ALGORITHM} Credential=${accessKeyId}/${scope}, ` +
        `SignedHeaders=${signedHeaders}, Signature=${signature}`
      );
      return headers;
    },
  };
}
