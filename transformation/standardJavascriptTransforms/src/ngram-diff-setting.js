/**
 * Transforms either an index template or index to include 'max_ngram_diff' with
 * the value specified below.  This limit was added in more recent versions of Opensearch
 * and needs to be used to unblock some scenarios.
 */
function main(context) {
  const NGRAM_DIFF_ALLOWED = 30;

  const hasNgram = (obj) => {
    if (!obj) return false;
    
    if (obj instanceof Map) {
      for (const [key, value] of obj) {
        if (String(value).toLowerCase().includes('ngram')) return true;
        if (hasNgram(value)) return true;
      }
      return false;
    }
    
    if (typeof obj === 'object') {
      for (const [key, value] of Object.entries(obj)) {
        if (String(value).toLowerCase().includes('ngram')) return true;
        if (hasNgram(value)) return true;
      }
    }
    
    return false;
  };

  const processDocument = (doc) => {
    if (doc?.body) {
      if (hasNgram(doc.body)) {
        const body = doc.body;
        
        if (body instanceof Map) {
          if (!body.has('settings')) {
            body.set('settings', new Map());
          }
          const settings = body.get('settings');
          settings.set('max_ngram_diff', NGRAM_DIFF_ALLOWED);
        } else if (typeof body === 'object') {
          if (!body.settings) {
            body.settings = {};
          }
          if (!body.settings.index) {
            body.settings.index = {};
          }
          body.settings.index.max_ngram_diff = NGRAM_DIFF_ALLOWED;
        }
      }
      return doc;
    }
 
    if (doc instanceof Map) {
      for (const [indexName, index] of doc) {
        if (index?.has('settings')) {
          if (hasNgram(index)) {
            const settings = index.get('settings');
            settings.set('max_ngram_diff', NGRAM_DIFF_ALLOWED);
          }
        }
      }
    }
    return doc;
  };

  return (document) => {
    if (Array.isArray(document)) {
      return document.map(processDocument);
    }
    return processDocument(document);
  };
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = main;
}

(() => main)();
