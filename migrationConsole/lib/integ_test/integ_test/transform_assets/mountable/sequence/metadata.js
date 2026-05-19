function getValue(container, key) {
  if (!container) return undefined;
  if (typeof container.get === "function") return container.get(key);
  return container[key];
}

function setValue(container, key, value) {
  if (!container || typeof container !== "object") return;
  if (typeof container.set === "function") {
    container.set(key, value);
  } else if (typeof container.put === "function") {
    container.put(key, value);
  } else {
    container[key] = value;
  }
}

function ensureChildContainer(container, key) {
  let value = getValue(container, key);
  if (!value) {
    value = typeof container?.get === "function" || typeof container?.put === "function" ? new Map() : {};
    setValue(container, key, value);
  }
  return value;
}

function getEntries(container) {
  if (!container || typeof container !== "object") return [];
  if (container instanceof Map) return Array.from(container.entries());
  if (typeof container.entries === "function") {
    try {
      return Array.from(container.entries());
    } catch {
      return [];
    }
  }
  return Object.entries(container);
}

function addFieldToMappingNode(mappingNode, fieldName, fieldType) {
  const directProperties = getValue(mappingNode, "properties");
  if (directProperties) {
    setValue(directProperties, fieldName, new Map([["type", fieldType]]));
    return;
  }

  let foundTypedMapping = false;
  for (const [, value] of getEntries(mappingNode)) {
    const typedProperties = getValue(value, "properties");
    if (typedProperties) {
      setValue(typedProperties, fieldName, new Map([["type", fieldType]]));
      foundTypedMapping = true;
    }
  }

  if (!foundTypedMapping) {
    const properties = ensureChildContainer(mappingNode, "properties");
    setValue(properties, fieldName, new Map([["type", fieldType]]));
  }
}

function main(context) {
  const fieldName = getValue(context, "fieldName") || "mountable_sequence_metadata_transform";
  const fieldType = getValue(context, "fieldType") || "keyword";

  return (metadata) => {
    const body = getValue(metadata, "body");
    if (!body) return metadata;

    const mappings = ensureChildContainer(body, "mappings");
    if (Array.isArray(mappings)) {
      mappings.forEach((mapping) => addFieldToMappingNode(mapping, fieldName, fieldType));
    } else {
      addFieldToMappingNode(mappings, fieldName, fieldType);
    }
    return metadata;
  };
}

(() => main)();
