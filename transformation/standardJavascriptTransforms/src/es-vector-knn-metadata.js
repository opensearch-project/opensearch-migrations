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
            const { dims, similarity, index_options: opts } = def;
            const method = {
                name:       'hnsw',
                engine:     'lucene',
                space_type: mapSimilarity(similarity),
            };
            // Only include explicit HNSW parameters when the source field had index_options.
            // Omitting parameters lets OpenSearch use its own defaults, which avoids
            // NPE in OS 3.0+ when m/ef_construction would otherwise be null.
            if (opts) {
                const { m, ef_construction: efConstr } = opts;
                const params = { encoder: { name: 'sq' } };
                if (m != null) params.m = m;
                if (efConstr != null) params.ef_construction = efConstr;
                method.parameters = params;
            }
            return { type: 'knn_vector', dimension: dims, method };
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

if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();