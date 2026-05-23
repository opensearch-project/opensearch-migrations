function main(context) {
  const sourceFieldName = context.get("sourceFieldName") || "mountable_sequence_document_first";
  const fieldName = context.get("fieldName") || "mountable_sequence_document_second";
  const valuePrefix = context.get("valuePrefix") || "second-transform-after-";

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
