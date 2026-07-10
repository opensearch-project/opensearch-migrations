function main(context) {
  const headerName = String(context.get("headerName")).trim();
  const headerValue = String(context.get("headerValue")).trim();

  return (tuple) => {
    const targetRequest = tuple.get("targetRequest");
    if (targetRequest) {
      targetRequest.set(headerName, `${headerName}: ${headerValue}`);
    }
    return tuple;
  };
}

(() => main)();
