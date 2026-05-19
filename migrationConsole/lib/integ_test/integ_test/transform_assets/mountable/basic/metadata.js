function isMapLike(value) {
  return value && typeof value === "object" && typeof value.get === "function";
}

function getValue(container, key) {
  if (!container) return undefined;
  if (isMapLike(container)) return container.get(key);
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

function makeChildContainer(parent) {
  return isMapLike(parent) || typeof parent?.put === "function" ? new Map() : {};
}

function makeFieldDefinition(parent, fieldType) {
  return isMapLike(parent) || typeof parent?.put === "function"
    ? new Map([["type", fieldType]])
    : {type: fieldType};
}

function ensureChildContainer(container, key) {
  let value = getValue(container, key);
  if (!value) {
    value = makeChildContainer(container);
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
  if (typeof container.entrySet === "function") {
    try {
      return Array.from(container.entrySet()).map((entry) => [entry.getKey(), entry.getValue()]);
    } catch {
      return [];
    }
  }
  return Object.entries(container);
}

function addFieldToProperties(properties, fieldName, fieldType) {
  setValue(properties, fieldName, makeFieldDefinition(properties, fieldType));
}

function addFieldToMappingNode(mappingNode, fieldName, fieldType) {
  if (!mappingNode || typeof mappingNode !== "object") return false;

  const directProperties = getValue(mappingNode, "properties");
  if (directProperties) {
    addFieldToProperties(directProperties, fieldName, fieldType);
    return true;
  }

  let foundTypedMapping = false;
  for (const [, value] of getEntries(mappingNode)) {
    const typedProperties = getValue(value, "properties");
    if (typedProperties) {
      addFieldToProperties(typedProperties, fieldName, fieldType);
      foundTypedMapping = true;
    }
  }

  if (foundTypedMapping) return true;

  const properties = ensureChildContainer(mappingNode, "properties");
  addFieldToProperties(properties, fieldName, fieldType);
  return true;
}

function addMetadataFieldMapping(mappings, fieldName, fieldType) {
  if (Array.isArray(mappings)) {
    mappings.forEach((mapping) => addFieldToMappingNode(mapping, fieldName, fieldType));
    return;
  }
  addFieldToMappingNode(mappings, fieldName, fieldType);
}

function main(context) {
  const fieldName = getValue(context, "fieldName") || "mountable_basic_metadata_transform";
  const fieldType = getValue(context, "fieldType") || "keyword";

  return (metadata) => {
    const body = getValue(metadata, "body");
    if (!body) return metadata;

    const mappings = ensureChildContainer(body, "mappings");
    addMetadataFieldMapping(mappings, fieldName, fieldType);
    return metadata;
  };
}

(() => main)();
