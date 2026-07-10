function main(context) {
  const fieldName = String(context.get("fieldName") || "mountable_sequence_document_first").trim();
  const fieldValue = String(context.get("fieldValue") || "first-document-transform").trim();

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
