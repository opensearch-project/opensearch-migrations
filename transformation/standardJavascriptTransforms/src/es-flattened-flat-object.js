function main(context) {
    const rules = [
        {
            when: { type: "flattened" },
            set: { type: "flat_object" },
            remove: ["index"]
        }
    ];

    function applyRules(node, rules) {
        if (Array.isArray(node)) {
            node.forEach((child) => applyRules(child, rules));
        } else if (node instanceof Map) {
            for (const { when, set, remove = [], rename = {} } of rules) {
                const matches = Object.entries(when).every(([k, v]) => {
                    const val = node.get(k);
                    return typeof v === "function" ? v(val) : val === v;
                });

                if (matches) {
                    for (const [k, v] of Object.entries(set)) {
                        if (k !== "dimension" || !node.has(k)) {
                            node.set(k, v);
                        }
                    }

                    for (const [oldKey, newKey] of Object.entries(rename)) {
                        if (node.has(oldKey)) {
                            node.set(newKey, node.get(oldKey));
                            node.delete(oldKey);
                        }
                    }

                    remove.forEach((key) => node.delete(key));
                }
            }

            for (const child of node.values()) {
                applyRules(child, rules);
            }
        } else if (node && typeof node === "object") {
            for (const { when, set, remove = [], rename = {} } of rules) {
                const matches = Object.entries(when).every(([k, v]) => {
                    const val = node[k];
                    return typeof v === "function" ? v(val) : val === v;
                });

                if (matches) {
                    for (const [k, v] of Object.entries(set)) {
                        if (k !== "dimension" || !(k in node)) {
                            node[k] = v;
                        }
                    }

                    for (const [oldKey, newKey] of Object.entries(rename)) {
                        if (node[oldKey] !== undefined) {
                            node[newKey] = node[oldKey];
                            delete node[oldKey];
                        }
                    }

                    remove.forEach((key) => delete node[key]);
                }
            }

            Object.values(node).forEach((child) => applyRules(child, rules));
        }
    }

    return (doc) => {
        if (doc && doc.type && doc.name && doc.body) {
            applyRules(doc, rules);
            if (doc.type === "index" && doc.body.settings) {
                if (!doc.body.settings.index) {
                    doc.body.settings.index = {};
                }
                doc.body.settings.index.knn = true;
            }
        }
        return doc;
    };
}
(() => main)();