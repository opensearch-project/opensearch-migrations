const main = require('../src/es-vector-knn-metadata.js');

describe('es-vector-knn-metadata', () => {
    let transform;

    beforeEach(() => {
        transform = main({});
    });

    test('converts dense_vector with index_options to knn_vector with parameters', () => {
        const input = {
            body: {
                settings: new Map(),
                mappings: {
                    properties: {
                        my_vec: {
                            type: 'dense_vector',
                            dims: 128,
                            similarity: 'cosine',
                            index_options: { m: 16, ef_construction: 100 }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const field = result.body.mappings.properties.my_vec;

        expect(field.type).toBe('knn_vector');
        expect(field.dimension).toBe(128);
        expect(field.method.engine).toBe('lucene');
        expect(field.method.space_type).toBe('cosinesimil');
        expect(field.method.parameters).toBeDefined();
        expect(field.method.parameters.m).toBe(16);
        expect(field.method.parameters.ef_construction).toBe(100);
        expect(field.method.parameters.encoder).toEqual({ name: 'sq' });
    });

    test('converts simple dense_vector (no index_options) without null parameters', () => {
        // Reproduces the OS 3.7 NullPointerException: when index_options are absent,
        // the transformer must NOT emit m/ef_construction/encoder with null values,
        // because OpenSearch's lucene engine mapper throws NPE on null parameters.
        const input = {
            body: {
                settings: new Map(),
                mappings: {
                    properties: {
                        product_vector: { type: 'dense_vector', dims: 384 },
                        image_vector:   { type: 'dense_vector', dims: 768 }
                    }
                }
            }
        };

        const result = transform(input);
        const v384 = result.body.mappings.properties.product_vector;
        const v768 = result.body.mappings.properties.image_vector;

        expect(v384.type).toBe('knn_vector');
        expect(v384.dimension).toBe(384);
        expect(v384.method.engine).toBe('lucene');
        expect(v384.method.parameters).toBeUndefined();

        expect(v768.type).toBe('knn_vector');
        expect(v768.dimension).toBe(768);
        expect(v768.method.parameters).toBeUndefined();
    });

    test('converts dense_vector in dynamic_templates', () => {
        const input = {
            body: {
                settings: new Map(),
                mappings: {
                    dynamic_templates: [
                        {
                            vectors_768: {
                                match: '*_768',
                                mapping: { type: 'dense_vector', dims: 768 }
                            }
                        }
                    ],
                    properties: {}
                }
            }
        };

        const result = transform(input);
        const tmpl = result.body.mappings.dynamic_templates[0].vectors_768;

        expect(tmpl.mapping.type).toBe('knn_vector');
        expect(tmpl.mapping.dimension).toBe(768);
        expect(tmpl.mapping.method.engine).toBe('lucene');
        expect(tmpl.mapping.method.parameters).toBeUndefined();
        expect(tmpl.match).toBe('*_768');
    });

    test('sets index.knn: true on the index settings when conversion occurs', () => {
        const input = {
            body: {
                settings: new Map(),
                mappings: {
                    properties: {
                        v: { type: 'dense_vector', dims: 4 }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.settings.get('index.knn')).toBe(true);
    });

    test('maps similarity values correctly', () => {
        const cases = [
            { similarity: 'cosine',      expected: 'cosinesimil' },
            { similarity: 'dot_product', expected: 'innerproduct' },
            { similarity: 'l2',          expected: 'l2' },
            { similarity: undefined,     expected: 'l2' },
        ];

        for (const { similarity, expected } of cases) {
            const field = { type: 'dense_vector', dims: 4 };
            if (similarity !== undefined) field.similarity = similarity;
            const input = { body: { settings: new Map(), mappings: { properties: { v: field } } } };
            const result = transform(input);
            expect(result.body.mappings.properties.v.method.space_type).toBe(expected);
        }
    });
});
