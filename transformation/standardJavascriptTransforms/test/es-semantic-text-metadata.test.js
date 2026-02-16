const main = require('../src/es-semantic-text-metadata.js');

describe('es-semantic-text-metadata', () => {
    let transform;

    beforeEach(() => {
        transform = main({});
    });

    test('converts semantic_text to text', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        content: {
                            type: 'semantic_text',
                            inference_id: 'my-endpoint'
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const field = result.body.mappings.properties.content;

        expect(field.type).toBe('text');
        expect(field.inference_id).toBeUndefined();
    });

    test('removes all ES-specific inference properties', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        content: {
                            type: 'semantic_text',
                            inference_id: 'my-endpoint',
                            search_inference_id: 'my-search-endpoint',
                            model_settings: {
                                task_type: 'text_embedding',
                                dimensions: 384,
                                similarity: 'cosine'
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const field = result.body.mappings.properties.content;

        expect(field.type).toBe('text');
        expect(field.inference_id).toBeUndefined();
        expect(field.search_inference_id).toBeUndefined();
        expect(field.model_settings).toBeUndefined();
    });

    test('handles multiple semantic_text fields', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        content: {
                            type: 'semantic_text',
                            inference_id: 'endpoint-1'
                        },
                        summary: {
                            type: 'semantic_text',
                            inference_id: 'endpoint-2'
                        }
                    }
                }
            }
        };

        const result = transform(input);

        expect(result.body.mappings.properties.content.type).toBe('text');
        expect(result.body.mappings.properties.summary.type).toBe('text');
    });

    test('does not modify non-semantic_text fields', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        title: { type: 'text' },
                        count: { type: 'integer' },
                        content: {
                            type: 'semantic_text',
                            inference_id: 'my-endpoint'
                        }
                    }
                }
            }
        };

        const result = transform(input);

        expect(result.body.mappings.properties.title.type).toBe('text');
        expect(result.body.mappings.properties.count.type).toBe('integer');
        expect(result.body.mappings.properties.content.type).toBe('text');
    });

    test('handles nested properties', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        nested_field: {
                            type: 'nested',
                            properties: {
                                inner_content: {
                                    type: 'semantic_text',
                                    inference_id: 'my-endpoint'
                                }
                            }
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const inner = result.body.mappings.properties.nested_field.properties.inner_content;

        expect(inner.type).toBe('text');
        expect(inner.inference_id).toBeUndefined();
    });

    test('returns input unchanged when no mappings', () => {
        const input = { body: { settings: { index: { number_of_replicas: 0 } } } };
        const result = transform(input);
        expect(result).toEqual(input);
    });

    test('returns input unchanged when no semantic_text fields', () => {
        const input = {
            body: {
                mappings: {
                    properties: {
                        title: { type: 'text' },
                        tag: { type: 'keyword' }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.title.type).toBe('text');
        expect(result.body.mappings.properties.tag.type).toBe('keyword');
    });

    test('works with Map instances', () => {
        const content = new Map([
            ['type', 'semantic_text'],
            ['inference_id', 'my-endpoint'],
            ['search_inference_id', 'my-search-endpoint']
        ]);
        const properties = new Map([['content', content]]);
        const mappings = new Map([['properties', properties]]);
        const body = new Map([['mappings', mappings]]);
        const input = { body };

        const result = transform(input);

        expect(content.get('type')).toBe('text');
        expect(content.has('inference_id')).toBe(false);
        expect(content.has('search_inference_id')).toBe(false);
    });
});
