function main(context) {
  const headerName = "x-mountable-string-context-transform";
  const headerValue = String(context);

  return (tuple) => {
    const targetRequest = tuple.get("targetRequest");
    if (targetRequest) {
      targetRequest.set(headerName, `${headerName}: ${headerValue}`);
    }
    return tuple;
  };
}

(() => main)();
