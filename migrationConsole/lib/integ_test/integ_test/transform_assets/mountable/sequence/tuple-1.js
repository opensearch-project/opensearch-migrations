function main(context) {
  const headerName = context.get("headerName");
  const headerValue = context.get("headerValue");

  return (tuple) => {
    const targetRequest = tuple.get("targetRequest");
    if (targetRequest) {
      targetRequest.set(headerName, `${headerName}: ${headerValue}`);
    }
    return tuple;
  };
}

(() => main)();
