function main(context) {
  const headerName = String(context.get("headerName")).trim();
  const headerValue = String(context.get("headerValue")).trim();

  return (request) => {
    let headers = request.get("headers");
    if (!headers) {
      headers = new Map();
      request.set("headers", headers);
    }
    headers.set(headerName, headerValue);
    return request;
  };
}

(() => main)();
