function main(context) {
  const headerName = context.get("headerName");
  const headerValue = context.get("headerValue");

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
