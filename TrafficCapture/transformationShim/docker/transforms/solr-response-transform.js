(function(bindings) {
  return function(msg) {
    var payload = msg.payload;
    if (payload && payload.inlinedTextBody) {
      var osResp = JSON.parse(payload.inlinedTextBody);
      if (osResp.hits) {
        var docs = [];
        for (var i = 0; i < osResp.hits.hits.length; i++) {
          docs.push(osResp.hits.hits[i]._source);
        }
        payload.inlinedTextBody = JSON.stringify({
          responseHeader: { status: 0, QTime: 0 },
          response: { numFound: osResp.hits.total.value, start: 0, docs: docs }
        });
      }
    }
    return msg;
  };
})
