/**
 * Solr → OpenSearch request transform.
 *
 * Rewrites `/solr/{collection}/select?q=...` requests into
 * `POST /{collection}/_search` with a match_all query body.
 */
import type { HttpRequestMessage } from '../types';

export function transform(msg: HttpRequestMessage): HttpRequestMessage {
  const match = /\/solr\/([^/]+)\/select/.exec(msg.URI);
  if (match) {
    msg.URI = '/' + match[1] + '/_search';
    msg.method = 'POST';
    msg.payload = { inlinedTextBody: JSON.stringify({ query: { match_all: {} } }) };
    msg.headers = msg.headers || {};
    msg.headers['content-type'] = 'application/json';
  }
  return msg;
}
