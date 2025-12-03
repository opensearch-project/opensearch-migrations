/**
 * Transforms either an index template or index to include 'max_ngram_diff' with
 * the value specified below.  This limit was added in more recent versions of Opensearch
 * and needs to be used to unblock some scenarios.
 */
function main(context) {
  const NGRAM_DIFF_ALLOWED = 30;

  const containsNgram = (value) => String(value).toLowerCase().includes('ngram');

  const hasNgram = (obj) => {
    if (!obj || typeof obj !== 'object') return false;
    
    const values = obj instanceof Map ? Array.from(obj.values()) : Object.values(obj);
    return values.some(value => containsNgram(value) || hasNgram(value));
  };

  const setNgramDiffForMap = (body) => {
    if (!body.has('settings')) {
      body.set('settings', new Map());
    }
    body.get('settings').set('max_ngram_diff', NGRAM_DIFF_ALLOWED);
  };

  const setNgramDiffForObject = (body) => {
    body.settings = body.settings || {};
    body.settings.index = body.settings.index || {};
    body.settings.index.max_ngram_diff = NGRAM_DIFF_ALLOWED;
  };

  const processBodyDocument = (doc) => {
    if (!hasNgram(doc.body)) return doc;

    if (doc.body instanceof Map) {
      setNgramDiffForMap(doc.body);
    } else {
      setNgramDiffForObject(doc.body);
    }
    return doc;
  };

  const processMapDocument = (doc) => {
    for (const index of doc.values()) {
      if (index?.has('settings') && hasNgram(index)) {
        index.get('settings').set('max_ngram_diff', NGRAM_DIFF_ALLOWED);
      }
    }
    return doc;
  };

  const processDocument = (doc) => {
    if (doc?.body) {
      return processBodyDocument(doc);
    }
    if (doc instanceof Map) {
      return processMapDocument(doc);
    }
    return doc;
  };

  return (document) => {
    return Array.isArray(document) ? document.map(processDocument) : processDocument(document);
  };
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = main;
}

(() => main)();
