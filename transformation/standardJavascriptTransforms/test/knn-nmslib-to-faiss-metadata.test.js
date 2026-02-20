const main = require('../src/knn-nmslib-to-faiss-metadata.js');

describe('knn-nmslib-to-faiss-metadata', () => {
    let transform;

    beforeEach(() => {
        transform = main({});
    });

    test('converts nmslib engine to faiss', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        my_vector: {
                            type: 'knn_vector',
                            dimension: 128,
                            method: {
                                name: 'hnsw',
                                engine: 'nmslib',
                                space_type: 'l2',
                                parameters: {
                                    m: 16,
                                    ef_construction: 100
                                }
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const field = result.body.mappings.properties.my_vector;

        expect(field.type).toBe('knn_vector');
        expect(field.dimension).toBe(128);
        expect(field.method.engine).toBe('faiss');
        expect(field.method.name).toBe('hnsw');
        expect(field.method.space_type).toBe('l2');
        expect(field.method.parameters.m).toBe(16);
        expect(field.method.parameters.ef_construction).toBe(100);
    });

    test('preserves cosinesimil space type', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        my_vector: {
                            type: 'knn_vector',
                            dimension: 64,
                            method: {
                                name: 'hnsw',
                                engine: 'nmslib',
                                space_type: 'cosinesimil',
                                parameters: { m: 32, ef_construction: 200 }
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.my_vector.method.space_type).toBe('cosinesimil');
    });

    test('does not modify faiss engine', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        my_vector: {
                            type: 'knn_vector',
                            dimension: 128,
                            method: {
                                name: 'hnsw',
                                engine: 'faiss',
                                space_type: 'l2'
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.my_vector.method.engine).toBe('faiss');
    });

    test('does not modify lucene engine', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        my_vector: {
                            type: 'knn_vector',
                            dimension: 128,
                            method: {
                                name: 'hnsw',
                                engine: 'lucene',
                                space_type: 'l2'
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.my_vector.method.engine).toBe('lucene');
    });

    test('uses defaults when parameters missing', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        my_vector: {
                            type: 'knn_vector',
                            dimension: 64,
                            method: {
                                name: 'hnsw',
                                engine: 'nmslib',
                                space_type: 'l2'
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.my_vector.method.parameters.m).toBe(16);
        expect(result.body.mappings.properties.my_vector.method.parameters.ef_construction).toBe(100);
    });

    test('handles nested properties', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        nested_field: {
                            type: 'nested',
                            properties: {
                                inner_vector: {
                                    type: 'knn_vector',
                                    dimension: 32,
                                    method: {
                                        name: 'hnsw',
                                        engine: 'nmslib',
                                        space_type: 'l2',
                                        parameters: { m: 8, ef_construction: 50 }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const innerVector = result.body.mappings.properties.nested_field.properties.inner_vector;
        expect(innerVector.method.engine).toBe('faiss');
    });
});
