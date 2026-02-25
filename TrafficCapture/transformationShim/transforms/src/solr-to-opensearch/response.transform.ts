/**
 * OpenSearch → Solr response transform.
 *
 * Converts OpenSearch `hits.hits[]._source` response format into
 * Solr's `response.docs[]` format with responseHeader.
 */
import type { HttpResponseMessage } from '../types';

interface OpenSearchHit {
  _source: Record<string, unknown>;
}

interface OpenSearchResponse {
  hits?: {
    total: { value: number };
    hits: OpenSearchHit[];
  };
}

export function transform(msg: HttpResponseMessage): HttpResponseMessage {
  const payload = msg.payload;
  if (payload?.inlinedTextBody) {
    const osResp: OpenSearchResponse = JSON.parse(payload.inlinedTextBody);
    if (osResp.hits) {
      const docs = osResp.hits.hits.map((hit) => hit._source);
      payload.inlinedTextBody = JSON.stringify({
        responseHeader: { status: 0, QTime: 0 },
        response: { numFound: osResp.hits.total.value, start: 0, docs },
      });
    }
  }
  return msg;
}
