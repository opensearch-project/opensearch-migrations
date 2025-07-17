function main(context) {
    /**
     * Recursively find any `{ type: 'dense_vector' }` field definitions and
     * rewrite them in-place to the new `knn_vector` format.  A single loop
     * handles both `Map` and plain–object nodes, so the check for
     * `val.type === 'dense_vector'` appears only once.
     */
    function applyTransformation(raw) {
        const mappings = raw?.body?.mappings;

        if (!mappings) return raw;

        let changed = false;

        const buildKnn = (def) => {
            const { dims, similarity, index_options: opts = {} } = def;
            const { m, ef_construction: efConstr } = opts;
            return {
                type: 'knn_vector',
                dimension: dims,
                method: {
                    name:       'hnsw',
                    engine:     'lucene',
                    space_type: mapSimilarity(similarity),
                    parameters: {
                        encoder: {
                            name: "sq"
                        },
                        m: m,
                        ef_construction: efConstr
                    }
                }
            };
        };

        const recurse = (node) => {
            if (!node || typeof node !== 'object') return;

            // Iterate entries uniformly, regardless of Map or plain object
            const entries = node instanceof Map ? node.entries() : Object.entries(node);

            for (const [key, val] of entries) {
                if (val && typeof val === 'object') {
                    if (val.type === 'dense_vector') {
                        const knn = buildKnn(val);
                        if (node instanceof Map) {
                            node.set(key, knn)
                        } else {
                            node[key] = knn
                        }
                        changed = true;
                    } else {
                        recurse(val);
                    }
                }
            }
        };

        recurse(mappings);

        if (changed) {
            if (!raw.body.settings) {
                raw.body.put("settings", new Map())
            }
            raw.body.settings.set("index.knn", true);
        }

        return raw;
    }

    const mapSimilarity = (sim) => {
        switch (sim) {
            case 'cosine':      return 'cosinesimil';
            case 'dot_product': return 'innerproduct';
            case 'l2':
            default:            return 'l2';
        }
    };

    return applyTransformation;
}

(() => main)();