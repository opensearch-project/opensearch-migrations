function applyRules(node, rules) {
  if (Array.isArray(node)) {
    node.forEach((child) => applyRules(child, rules));
  } else if (node && typeof node === "object") {
    for (const { when, set, remove = [] } of rules) {
      const matches = Object.entries(when).every(([k, v]) => node[k] === v);
      if (matches) {
        // apply the rule
        Object.assign(node, set);
        remove.forEach((key) => delete node[key]);
      }
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
