function main(context) {
  const fieldName = context.get("fieldName") || "mountable_basic_document_transform";
  const fieldValue = context.get("fieldValue") || "basic-document-transform";

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
