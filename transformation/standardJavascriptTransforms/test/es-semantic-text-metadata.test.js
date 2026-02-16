const main = require('../src/es-semantic-text-metadata.js');

describe('es-semantic-text-metadata', () => {
    let transform;

    beforeEach(() => {
        transform = main({});
    });

    test('converts semantic_text to semantic and renames inference_id to model_id', () => {
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

        expect(field.type).toBe('semantic');
        expect(field.model_id).toBe('my-endpoint');
        expect(field.inference_id).toBeUndefined();
    });

    test('renames search_inference_id to search_model_id and removes model_settings', () => {
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

        expect(field.type).toBe('semantic');
        expect(field.model_id).toBe('my-endpoint');
        expect(field.search_model_id).toBe('my-search-endpoint');
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

        expect(result.body.mappings.properties.content.type).toBe('semantic');
        expect(result.body.mappings.properties.content.model_id).toBe('endpoint-1');
        expect(result.body.mappings.properties.summary.type).toBe('semantic');
        expect(result.body.mappings.properties.summary.model_id).toBe('endpoint-2');
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
        expect(result.body.mappings.properties.content.type).toBe('semantic');
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

        expect(inner.type).toBe('semantic');
        expect(inner.model_id).toBe('my-endpoint');
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

        expect(content.get('type')).toBe('semantic');
        expect(content.get('model_id')).toBe('my-endpoint');
        expect(content.get('search_model_id')).toBe('my-search-endpoint');
        expect(content.has('inference_id')).toBe(false);
        expect(content.has('search_inference_id')).toBe(false);
    });
});

describe('es-semantic-text-metadata with model_mappings', () => {
    test('uses model_mappings to resolve actual OS model_ids', () => {
        const transform = main({
            model_mappings: {
                'my-e5-endpoint': 'os-model-abc123',
                'my-search-endpoint': 'os-model-xyz789'
            }
        });

        const input = {
            body: {
                mappings: {
                    properties: {
                        content: {
                            type: 'semantic_text',
                            inference_id: 'my-e5-endpoint',
                            search_inference_id: 'my-search-endpoint'
                        }
                    }
                }
            }
        };

        const result = transform(input);
        const field = result.body.mappings.properties.content;

        expect(field.type).toBe('semantic');
        expect(field.model_id).toBe('os-model-abc123');
        expect(field.search_model_id).toBe('os-model-xyz789');
    });

    test('falls back to inference_id when not in model_mappings', () => {
        const transform = main({
            model_mappings: {
                'my-e5-endpoint': 'os-model-abc123'
            }
        });

        const input = {
            body: {
                mappings: {
                    properties: {
                        content: {
                            type: 'semantic_text',
                            inference_id: 'unknown-endpoint'
                        }
                    }
                }
            }
        };

        const result = transform(input);
        expect(result.body.mappings.properties.content.model_id).toBe('unknown-endpoint');
    });

    test('works with Map-based model_mappings context', () => {
        const modelMappings = new Map([
            ['my-e5-endpoint', 'os-model-abc123']
        ]);
        const context = new Map([
            ['model_mappings', modelMappings]
        ]);
        const transform = main(context);

        const content = new Map([
            ['type', 'semantic_text'],
            ['inference_id', 'my-e5-endpoint']
        ]);
        const properties = new Map([['content', content]]);
        const mappings = new Map([['properties', properties]]);
        const body = new Map([['mappings', mappings]]);
        const input = { body };

        transform(input);

        expect(content.get('type')).toBe('semantic');
        expect(content.get('model_id')).toBe('os-model-abc123');
    });

    test('handles multiple fields with different model_mappings', () => {
        const transform = main({
            model_mappings: {
                'endpoint-1': 'os-model-111',
                'endpoint-2': 'os-model-222'
            }
        });

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
        expect(result.body.mappings.properties.content.model_id).toBe('os-model-111');
        expect(result.body.mappings.properties.summary.model_id).toBe('os-model-222');
    });
});
