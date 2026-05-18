function main(context) {
  const fieldName = context.get("fieldName") || "mountable_sequence_document_first";
  const fieldValue = context.get("fieldValue") || "first-document-transform";

  return (documents) => {
    for (const doc of documents) {
      const body = doc.get("document");
      if (body) {
        body.set(fieldName, fieldValue);
      }
    }
    return documents;
  };
}

(() => main)();
