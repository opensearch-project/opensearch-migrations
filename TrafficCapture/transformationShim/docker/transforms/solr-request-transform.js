(function(bindings) {
  return function(msg) {
    var match = msg.URI.match(/\/solr\/([^\/]+)\/select/);
    if (match) {
      msg.URI = '/' + match[1] + '/_search';
      msg.method = 'POST';
      msg.payload = { inlinedTextBody: JSON.stringify({query:{match_all:{}}}) };
      if (!msg.headers) msg.headers = {};
      msg.headers['content-type'] = 'application/json';
    }
    return msg;
  };
})
