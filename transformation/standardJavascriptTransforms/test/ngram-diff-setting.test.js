const main = require("../src/ngram-diff-setting");

describe('NGramDiffSetting Transformer', () => {
  const NGRAM_DIFF_ALLOWED = 30;
  
  test('null context is handled', () => {
    const transformer = main(null);
    expect(transformer).toBeInstanceOf(Function);
  });
  
  test('processes a single document', () => {
    const transformer = main(null);
    const doc = { type: 'random', data: 'test' };
    const result = transformer(doc);
    expect(result).toBe(doc);
  });
  
  test('processes an array of documents', () => {
    const transformer = main(null);
    const docs = [
      { type: 'random', data: 'test1' },
      { type: 'random', data: 'test2' }
    ];
    const result = transformer(docs);
    expect(result).toEqual(docs);
    expect(Array.isArray(result)).toBe(true);
  });
  
  describe('Template Creation Requests', () => {
    test('detects template creation request', () => {
      const transformer = main(null);
      const doc = {
        type: 'template',
        name: 'test-template',
        body: {}
      };
      const result = transformer(doc);
      expect(result).toBe(doc);
    });
    
    test('detects indexTemplate creation request', () => {
      const transformer = main(null);
      const doc = {
        type: 'indexTemplate',
        name: 'test-template',
        body: {}
      };
      const result = transformer(doc);
      expect(result).toBe(doc);
    });
    
    test('detects componentTemplate creation request', () => {
      const transformer = main(null);
      const doc = {
        type: 'componentTemplate',
        name: 'test-template',
        body: {}
      };
      const result = transformer(doc);
      expect(result).toBe(doc);
    });
  });
  
  describe('NGram Detection', () => {
    test('detects ngram tokenizer', () => {
      const transformer = main(null);
      const body = new Map([
        ['settings', new Map([
          ['analysis', new Map([
            ['tokenizer', new Map([
              ['my_tokenizer', new Map([
                ['type', 'ngram'],
                ['min_gram', 3],
                ['max_gram', 4]
              ])]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'template', name: 'test-template', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
    });
    
    test('detects edge_ngram tokenizer', () => {
      const transformer = main(null);
      const body = new Map([
        ['settings', new Map([
          ['analysis', new Map([
            ['tokenizer', new Map([
              ['my_tokenizer', new Map([
                ['type', 'edge_ngram'],
                ['min_gram', 1],
                ['max_gram', 15]
              ])]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'template', name: 'test-template', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
    });
    
    test('detects ngram filter', () => {
      const transformer = main(null);
      const body = new Map([
        ['settings', new Map([
          ['analysis', new Map([
            ['filter', new Map([
              ['my_filter', new Map([
                ['type', 'ngram'],
                ['min_gram', 1],
                ['max_gram', 20]
              ])]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'template', name: 'test-template', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
    });
    
    test('does not modify non-ngram templates', () => {
      const transformer = main(null);
      const body = new Map([
        ['settings', new Map([
          ['analysis', new Map([
            ['analyzer', new Map([
              ['my_analyzer', new Map([['type', 'standard']])]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'template', name: 'test-template', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').has('max_ngram_diff')).toBe(false);
    });
  });
  
  describe('Setting Addition', () => {
    test('adds index.max_ngram_diff to existing settings', () => {
      const transformer = main(null);
      const body = new Map([
        ['settings', new Map([
          ['index', new Map([['number_of_shards', 5]])],
          ['analysis', new Map([
            ['filter', new Map([
              ['my_filter', new Map([
                ['type', 'edge_ngram'],
                ['min_gram', 1],
                ['max_gram', 15]
              ])]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'template', name: 'test-template', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
      expect(result.body.get('settings').get('index').get('number_of_shards')).toBe(5);
    });

    test('adds index.max_ngram_diff to an index', () => {
      const transformer = main(null);
      const body = new Map([
        ['settings', new Map([
          ['index', new Map([['number_of_shards', 5]])],
          ['analysis', new Map([
            ['filter', new Map([
              ['my_filter', new Map([
                ['type', 'edge_ngram'],
                ['min_gram', 1],
                ['max_gram', 15]
              ])]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'index', name: 'test-index', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
      expect(result.body.get('settings').get('index').get('number_of_shards')).toBe(5);
    });
    
    test('creates settings structure when missing', () => {
      const transformer = main(null);
      const body = new Map([
        ['analysis', new Map([
          ['tokenizer', new Map([
            ['my_tokenizer', new Map([
              ['type', 'ngram'],
              ['min_gram', 2],
              ['max_gram', 5]
            ])]
          ])]
        ])]
      ]);
      const doc = { type: 'template', name: 'test-template', body };
      
      const result = transformer(doc);
      expect(result.body.get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
    });
  });
  
  describe('Error Handling', () => {
    test('handles errors gracefully', () => {
      const transformer = main(null);
      const doc = {
        type: 'template',
        name: 'test-template',
        body: null  // This will cause an error in processing
      };
      
      // Should not throw
      const result = transformer(doc);
      expect(result).toBe(doc);
    });
  });

  describe('Plain Object Support', () => {
    test('adds max_ngram_diff to plain object body', () => {
      const transformer = main(null);
      const doc = {
        type: 'template',
        name: 'test-template',
        body: {
          settings: {
            analysis: {
              tokenizer: {
                my_tokenizer: {
                  type: 'ngram',
                  min_gram: 2,
                  max_gram: 5
                }
              }
            }
          }
        }
      };
      
      const result = transformer(doc);
      expect(result.body.settings.index.max_ngram_diff).toBe(NGRAM_DIFF_ALLOWED);
    });
  });

  describe('Map Document Support', () => {
    test('processes Map document with ngram settings', () => {
      const transformer = main(null);
      const doc = new Map([
        ['test-index', new Map([
          ['settings', new Map([
            ['analysis', new Map([
              ['tokenizer', new Map([
                ['my_tokenizer', new Map([
                  ['type', 'ngram'],
                  ['min_gram', 2],
                  ['max_gram', 5]
                ])]
              ])]
            ])]
          ])]
        ])]
      ]);
      
      const result = transformer(doc);
      expect(result.get('test-index').get('settings').get('max_ngram_diff')).toBe(NGRAM_DIFF_ALLOWED);
    });
  });
});
