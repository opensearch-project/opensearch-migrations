function main(context) {
  const headerName = context.get("headerName") || "x-mountable-basic-tuple-transform";
  const headerValue = context.get("headerValue") || "basic-tuple-transform";

  return (tuple) => {
    const targetRequest = tuple.get("targetRequest");
    if (targetRequest) {
      targetRequest.set(headerName, `${headerName}: ${headerValue}`);
    }
    return tuple;
  };
}

(() => main)();
