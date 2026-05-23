function main(context) {
  const headerName = context.get("headerName") || "x-mountable-basic-request-transform";
  const headerValue = context.get("headerValue") || "basic-request-transform";

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
