function main(context) {
  const sourceFieldName = String(context.get("sourceFieldName") || "mountable_sequence_document_first").trim();
  const fieldName = String(context.get("fieldName") || "mountable_sequence_document_second").trim();
  const valuePrefix = String(context.get("valuePrefix") || "second-transform-after-").trim();

  return (documents) => {
    for (const doc of documents) {
      const body = doc.get("document");
      if (body) {
        const sourceFieldValue = body.get(sourceFieldName);
        body.set(fieldName, `${valuePrefix}${sourceFieldValue}`);
      }
    }
    return documents;
  };
}

(() => main)();
