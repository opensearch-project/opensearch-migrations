function applyRulesToMap(when, set, remove, map) {
  const matches = Object.entries(when).every(([k, v]) => map.get(k) === v);
  if (matches) {
    Object.entries(set).every(([k, v]) => map.set(k, v));
    remove.forEach((key) => map.delete(key));
  }
}

function applyRulesToObject(when, set, remove, obj) {
  const matches = Object.entries(when).every(([k, v]) => obj[k] === v);
  if (matches) {
    Object.assign(obj, set);
    remove.forEach((key) => delete obj[key]);
  }
}

function applyRules(node, rules) {
  if (Array.isArray(node)) {
    node.forEach((child) => applyRules(child, rules));
  } else if (node instanceof Map) {
    for (const { when, set, remove = [] } of rules) {
      applyRulesToMap(when, set, remove, node);
    }
    // recurse
    for (const child of node.values()) {
      applyRules(child, rules);
    }
  } else if (node && typeof node === "object") {
    for (const { when, set, remove = [] } of rules) {
      applyRulesToObject(when, set, remove, node);
    }
    // recurse
    Object.values(node).forEach((child) => applyRules(child, rules));
  }
}

function main(context) {
  if (!context.rules) {
    throw Error(
      "Expected rules to be defined in the context.  Example: " +
        JSON.stringify(
          { rules: [{ when: { type: "foo" }, set: { type: "bar" } }] },
          null,
          2
        )
    );
  }

  return (doc) => {
    if (doc && doc.type && doc.name && doc.body) {
      applyRules(doc.body, context.rules);
    }
    return doc;
  };
}

module.exports = { main };
