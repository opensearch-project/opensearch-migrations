const main = require('../src/es-knn-index-to-field-level-metadata.js');

describe('es-knn-index-to-field-level-metadata', () => {
    let transform;

    beforeEach(() => {
        transform = main({});
    });

    test('lifts flat-keyed index-level knn params onto knn_vector field method', () => {
        // Mirrors the broken retail-products payload observed in dev:
        // ES 7.10 (k-NN plugin) → OS 3.3, settings stored as flat keys.
        const input = {
            body: {
                aliases: {},
                mappings: {
                    properties: {
                        description_embedding: {
                            type: 'knn_vector',
                            dimension: 1024
                        },
                        name: { type: 'text' }
                    }
                },
                settings: {
                    'index.knn': 'true',
                    'index.knn.algo_param.ef_construction': '128',
                    'index.knn.algo_param.ef_search': '100',
                    'index.knn.algo_param.m': '24',
                    'index.knn.space_type': 'l2',
                    number_of_replicas: 1,
                    number_of_shards: '2'
                }
            }
        };

        const result = transform(input);

        // Build-time index-level keys are gone
        expect(result.body.settings['index.knn.algo_param.ef_construction']).toBeUndefined();
        expect(result.body.settings['index.knn.algo_param.m']).toBeUndefined();
        expect(result.body.settings['index.knn.space_type']).toBeUndefined();
        // ef_search is a valid runtime setting on OS 2.x+ — leave it alone
        expect(result.body.settings['index.knn.algo_param.ef_search']).toBe('100');
        // index.knn=true is still valid in OS 2.x+
        expect(result.body.settings['index.knn']).toBe('true');
        // Non-knn settings preserved
        expect(result.body.settings.number_of_replicas).toBe(1);
        expect(result.body.settings.number_of_shards).toBe('2');

        // Method config injected at field level with numeric coercion
        const field = result.body.mappings.properties.description_embedding;
        expect(field.type).toBe('knn_vector');
        expect(field.dimension).toBe(1024);
        expect(field.method).toEqual({
            name: 'hnsw',
            engine: 'faiss',
            space_type: 'l2',
            parameters: {
                m: 24,
                ef_construction: 128
            }
        });
        // Non-vector fields untouched
        expect(result.body.mappings.properties.name).toEqual({ type: 'text' });
    });

    test('lifts nested-form index-level knn params', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        v: { type: 'knn_vector', dimension: 4 }
                    }
                },
                settings: {
                    index: {
                        knn: {
                            algo_param: {
                                ef_construction: 200,
                                m: 16
                            },
                            space_type: 'cosinesimil'
                        }
                    }
                }
            }
        };

        const result = transform(input);

        // Nested leaves are removed and emptied parents pruned
        expect(result.body.settings.index).toBeUndefined();
        expect(result.body.mappings.properties.v.method).toEqual({
            name: 'hnsw',
            engine: 'faiss',
            space_type: 'cosinesimil',
            parameters: {
                m: 16,
                ef_construction: 200
            }
        });
    });

    test('does not overwrite an existing field-level method', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        already_method: {
                            type: 'knn_vector',
                            dimension: 8,
                            method: {
                                name: 'hnsw',
                                engine: 'faiss',
                                space_type: 'l2',
                                parameters: { m: 8, ef_construction: 64 }
                            }
                        },
                        needs_method: {
                            type: 'knn_vector',
                            dimension: 8
                        }
                    }
                },
                settings: {
                    'index.knn.algo_param.m': '24',
                    'index.knn.algo_param.ef_construction': '128',
                    'index.knn.space_type': 'l2'
                }
            }
        };

        const result = transform(input);

        // Field that already had a method is left alone (engine still faiss).
        expect(result.body.mappings.properties.already_method.method.engine).toBe('faiss');
        expect(result.body.mappings.properties.already_method.method.parameters.m).toBe(8);

        // Field without method gets one synthesized from the index-level params.
        expect(result.body.mappings.properties.needs_method.method).toEqual({
            name: 'hnsw',
            engine: 'faiss',
            space_type: 'l2',
            parameters: { m: 24, ef_construction: 128 }
        });
    });

    test('handles knn_vector fields nested under "nested" or "object" types', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        outer: {
                            type: 'nested',
                            properties: {
                                inner_vec: { type: 'knn_vector', dimension: 4 }
                            }
                        }
                    }
                },
                settings: {
                    'index.knn.algo_param.m': '12',
                    'index.knn.algo_param.ef_construction': '96',
                    'index.knn.space_type': 'l2'
                }
            }
        };

        const result = transform(input);
        const innerVec = result.body.mappings.properties.outer.properties.inner_vec;
        expect(innerVec.method.parameters.m).toBe(12);
        expect(innerVec.method.parameters.ef_construction).toBe(96);
        expect(innerVec.method.space_type).toBe('l2');
    });

    test('is a no-op when no index-level knn params are present', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        v: {
                            type: 'knn_vector',
                            dimension: 4,
                            method: {
                                name: 'hnsw',
                                engine: 'lucene',
                                space_type: 'l2',
                                parameters: { m: 16, ef_construction: 100 }
                            }
                        }
                    }
                },
                settings: {
                    'index.knn': 'true',
                    number_of_shards: '1'
                }
            }
        };

        // Deep-clone via JSON for an unambiguous before/after comparison.
        const before = JSON.parse(JSON.stringify(input));
        const result = transform(input);
        expect(result).toEqual(before);
    });

    test('builds a method even when only some params are present', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        v: { type: 'knn_vector', dimension: 4 }
                    }
                },
                settings: {
                    // Only space_type — m and ef_construction are missing
                    'index.knn.space_type': 'l2'
                }
            }
        };

        const result = transform(input);
        expect(result.body.settings['index.knn.space_type']).toBeUndefined();
        const m = result.body.mappings.properties.v.method;
        expect(m.name).toBe('hnsw');
        expect(m.engine).toBe('faiss');
        expect(m.space_type).toBe('l2');
        // No `parameters` key when neither m nor ef_construction were supplied
        expect(m.parameters).toBeUndefined();
    });

    test('defaults space_type to l2 when the source omits it', () => {
        // The ES k-NN plugin defaulted space_type to l2, so a source that never
        // set index.knn.space_type still migrates to an explicit l2 method.
        const input = {
            body: {
                mappings: { properties: { v: { type: 'knn_vector', dimension: 4 } } },
                settings: {
                    'index.knn.algo_param.m': '16',
                    'index.knn.algo_param.ef_construction': '100'
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.v.method).toEqual({
            name: 'hnsw',
            engine: 'faiss',
            space_type: 'l2',
            parameters: { m: 16, ef_construction: 100 }
        });
    });

    test('preserves ef_search alone (no build-time params present)', () => {
        // ef_search is a valid runtime setting on OS — when it's the only
        // index-level knn key present there's nothing to do, so the body
        // round-trips unchanged.
        const input = {
            body: {
                mappings: { properties: { v: { type: 'knn_vector', dimension: 4 } } },
                settings: { 'index.knn.algo_param.ef_search': '100' }
            }
        };

        const before = JSON.parse(JSON.stringify(input));
        const result = transform(input);
        expect(result).toEqual(before);
    });

    test('strips build-time params but preserves ef_search alongside them', () => {
        const input = {
            body: {
                mappings: { properties: { v: { type: 'knn_vector', dimension: 4 } } },
                settings: {
                    'index.knn': 'true',
                    'index.knn.algo_param.ef_search': '100',
                    'index.knn.algo_param.ef_construction': '128',
                    'index.knn.algo_param.m': '24',
                    'index.knn.space_type': 'l2'
                }
            }
        };

        const result = transform(input);
        expect(result.body.settings['index.knn.algo_param.ef_construction']).toBeUndefined();
        expect(result.body.settings['index.knn.algo_param.m']).toBeUndefined();
        expect(result.body.settings['index.knn.space_type']).toBeUndefined();
        expect(result.body.settings['index.knn.algo_param.ef_search']).toBe('100');
        expect(result.body.mappings.properties.v.method.parameters.m).toBe(24);
    });

    test('handles a body backed by Map / settings backed by Map', () => {
        const settings = new Map();
        settings.set('index.knn.algo_param.m', '8');
        settings.set('index.knn.algo_param.ef_construction', '64');
        settings.set('index.knn.space_type', 'l2');

        const props = new Map();
        props.set('v', { type: 'knn_vector', dimension: 4 });
        const mappings = new Map();
        mappings.set('properties', props);

        const body = new Map();
        body.set('settings', settings);
        body.set('mappings', mappings);
        const raw = { body };

        const result = transform(raw);

        expect(result.body.get('settings').has('index.knn.algo_param.m')).toBe(false);
        const v = result.body.get('mappings').get('properties').get('v');
        expect(v.method.parameters.m).toBe(8);
        expect(v.method.parameters.ef_construction).toBe(64);
    });

    test('is a no-op for bodies with no settings at all', () => {
        const input = { body: { mappings: { properties: { name: { type: 'text' } } } } };
        const before = JSON.parse(JSON.stringify(input));
        const result = transform(input);
        expect(result).toEqual(before);
    });

    test.each(['l1', 'linf', 'L1', 'LINF'])(
        'falls back to l2 for nmslib-only space_type %s',
        (sourceSpaceType) => {
            const input = {
                body: {
                    mappings: { properties: { v: { type: 'knn_vector', dimension: 4 } } },
                    settings: {
                        'index.knn.algo_param.m': '16',
                        'index.knn.space_type': sourceSpaceType
                    }
                }
            };

            const result = transform(input);
            expect(result.body.mappings.properties.v.method.space_type).toBe('l2');
        }
    );

    test('preserves a faiss/lucene-supported space_type unchanged', () => {
        const input = {
            body: {
                mappings: { properties: { v: { type: 'knn_vector', dimension: 4 } } },
                settings: {
                    'index.knn.algo_param.m': '16',
                    'index.knn.space_type': 'innerproduct'
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.v.method.space_type).toBe('innerproduct');
    });

    test('honors an engine override from context', () => {
        const luceneTransform = main({ engine: 'lucene' });
        const input = {
            body: {
                mappings: { properties: { v: { type: 'knn_vector', dimension: 4 } } },
                settings: {
                    'index.knn.algo_param.m': '16',
                    'index.knn.algo_param.ef_construction': '100',
                    'index.knn.space_type': 'l2'
                }
            }
        };

        const result = luceneTransform(input);
        expect(result.body.mappings.properties.v.method.engine).toBe('lucene');
    });
});
