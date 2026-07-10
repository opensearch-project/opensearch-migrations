function main(context) {
  const previousHeaderName = String(context.get("previousHeaderName")).trim();
  const headerName = String(context.get("headerName")).trim();
  const headerValuePrefix = String(context.get("headerValuePrefix")).trim();

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
