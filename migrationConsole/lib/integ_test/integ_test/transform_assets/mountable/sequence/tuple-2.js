function main(context) {
  const previousHeaderName = context.get("previousHeaderName");
  const headerName = context.get("headerName");
  const headerValuePrefix = context.get("headerValuePrefix");

  return (tuple) => {
    const targetRequest = tuple.get("targetRequest");
    if (targetRequest) {
      const previousHeaderLine = String(targetRequest.get(previousHeaderName) || "");
      const separatorIndex = previousHeaderLine.indexOf(": ");
      const previousHeaderValue = separatorIndex >= 0
        ? previousHeaderLine.substring(separatorIndex + 2)
        : previousHeaderLine;
      targetRequest.set(headerName, `${headerName}: ${headerValuePrefix}${previousHeaderValue}`);
    }
    return tuple;
  };
}

(() => main)();
