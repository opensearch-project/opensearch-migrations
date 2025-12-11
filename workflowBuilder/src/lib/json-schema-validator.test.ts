/**
 * Tests for JSON Schema Validator using Ajv
 */

import { describe, it, expect } from 'vitest';
import {
  validateWithJsonSchema,
  createJsonSchemaValidator,
  parseWithJsonSchema,
  safeParseWithJsonSchema,
  ajvErrorToFieldErrors,
  createAjvInstance,
} from './json-schema-validator';
import type { JSONSchema7 } from '../types/json-schema.types';

describe('createAjvInstance', () => {
  it('should create an Ajv instance with correct configuration', () => {
    const ajv = createAjvInstance();
    
    expect(ajv).toBeDefined();
    expect(ajv.compile).toBeDefined();
  });

  it('should support format validators', () => {
    const ajv = createAjvInstance();
    const schema: JSONSchema7 = { type: 'string', format: 'email' };
    const validate = ajv.compile(schema);
    
    expect(validate('test@example.com')).toBe(true);
    expect(validate('invalid-email')).toBe(false);
  });
});

describe('ajvErrorToFieldErrors', () => {
  it('should convert Ajv errors to FieldError format', () => {
    const ajvErrors = [
      {
        instancePath: '/user/email',
        schemaPath: '#/properties/user/properties/email/format',
        keyword: 'format',
        params: { format: 'email' },
        message: 'must match format "email"',
      },
    ];
    
    const fieldErrors = ajvErrorToFieldErrors(ajvErrors);
    
    expect(fieldErrors).toHaveLength(1);
    expect(fieldErrors[0]).toMatchObject({
      path: 'user.email',
      message: 'must match format "email"',
      code: 'format',
      keyword: 'format',
    });
  });

  it('should handle required field errors', () => {
    const ajvErrors = [
      {
        instancePath: '/user',
        schemaPath: '#/properties/user/required',
        keyword: 'required',
        params: { missingProperty: 'email' },
        message: "must have required property 'email'",
      },
    ];
    
    const fieldErrors = ajvErrorToFieldErrors(ajvErrors);
    
    expect(fieldErrors[0].message).toBe('Missing required field: user.email');
  });

  it('should handle type errors', () => {
    const ajvErrors = [
      {
        instancePath: '/age',
        schemaPath: '#/properties/age/type',
        keyword: 'type',
        params: { type: 'number' },
        message: 'must be number',
      },
    ];
    
    const fieldErrors = ajvErrorToFieldErrors(ajvErrors);
    
    expect(fieldErrors[0].message).toBe('Must be of type number');
  });

  it('should handle enum errors', () => {
    const ajvErrors = [
      {
        instancePath: '/color',
        schemaPath: '#/properties/color/enum',
        keyword: 'enum',
        params: { allowedValues: ['red', 'green', 'blue'] },
        message: 'must be equal to one of the allowed values',
      },
    ];
    
    const fieldErrors = ajvErrorToFieldErrors(ajvErrors);
    
    expect(fieldErrors[0].message).toBe('Must be one of: "red", "green", "blue"');
  });

  it('should convert root path correctly', () => {
    const ajvErrors = [
      {
        instancePath: '',
        schemaPath: '#/type',
        keyword: 'type',
        params: { type: 'object' },
        message: 'must be object',
      },
    ];
    
    const fieldErrors = ajvErrorToFieldErrors(ajvErrors);
    
    expect(fieldErrors[0].path).toBe('');
  });
});

describe('validateWithJsonSchema', () => {
  describe('string schemas', () => {
    it('should validate basic string schema', () => {
      const schema: JSONSchema7 = { type: 'string' };
      
      expect(validateWithJsonSchema(schema, 'hello').success).toBe(true);
      expect(validateWithJsonSchema(schema, 123).success).toBe(false);
      expect(validateWithJsonSchema(schema, null).success).toBe(false);
    });

    it('should apply minLength constraint', () => {
      const schema: JSONSchema7 = { type: 'string', minLength: 3 };
      
      expect(validateWithJsonSchema(schema, 'abc').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'ab').success).toBe(false);
      expect(validateWithJsonSchema(schema, '').success).toBe(false);
    });

    it('should apply maxLength constraint', () => {
      const schema: JSONSchema7 = { type: 'string', maxLength: 5 };
      
      expect(validateWithJsonSchema(schema, 'hello').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'hello!').success).toBe(false);
    });

    it('should apply pattern constraint', () => {
      const schema: JSONSchema7 = { type: 'string', pattern: '^[a-z]+$' };
      
      expect(validateWithJsonSchema(schema, 'abc').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'ABC').success).toBe(false);
      expect(validateWithJsonSchema(schema, 'abc123').success).toBe(false);
    });

    it('should apply email format', () => {
      const schema: JSONSchema7 = { type: 'string', format: 'email' };
      
      expect(validateWithJsonSchema(schema, 'test@example.com').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'invalid-email').success).toBe(false);
    });

    it('should apply url format', () => {
      const schema: JSONSchema7 = { type: 'string', format: 'url' };
      
      expect(validateWithJsonSchema(schema, 'https://example.com').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'not-a-url').success).toBe(false);
    });

    it('should apply uri format', () => {
      const schema: JSONSchema7 = { type: 'string', format: 'uri' };
      
      expect(validateWithJsonSchema(schema, 'https://example.com/path').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'invalid').success).toBe(false);
    });

    it('should apply uuid format', () => {
      const schema: JSONSchema7 = { type: 'string', format: 'uuid' };
      
      expect(validateWithJsonSchema(schema, '550e8400-e29b-41d4-a716-446655440000').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'not-a-uuid').success).toBe(false);
    });

    it('should apply date-time format', () => {
      const schema: JSONSchema7 = { type: 'string', format: 'date-time' };
      
      expect(validateWithJsonSchema(schema, '2024-01-15T10:30:00Z').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'not-a-datetime').success).toBe(false);
    });
  });

  describe('number schemas', () => {
    it('should validate basic number schema', () => {
      const schema: JSONSchema7 = { type: 'number' };
      
      expect(validateWithJsonSchema(schema, 42).success).toBe(true);
      expect(validateWithJsonSchema(schema, 3.14).success).toBe(true);
      expect(validateWithJsonSchema(schema, '42').success).toBe(false);
    });

    it('should validate integer schema', () => {
      const schema: JSONSchema7 = { type: 'integer' };
      
      expect(validateWithJsonSchema(schema, 42).success).toBe(true);
      expect(validateWithJsonSchema(schema, 3.14).success).toBe(false);
    });

    it('should apply minimum constraint', () => {
      const schema: JSONSchema7 = { type: 'number', minimum: 10 };
      
      expect(validateWithJsonSchema(schema, 10).success).toBe(true);
      expect(validateWithJsonSchema(schema, 15).success).toBe(true);
      expect(validateWithJsonSchema(schema, 9).success).toBe(false);
    });

    it('should apply maximum constraint', () => {
      const schema: JSONSchema7 = { type: 'number', maximum: 100 };
      
      expect(validateWithJsonSchema(schema, 100).success).toBe(true);
      expect(validateWithJsonSchema(schema, 50).success).toBe(true);
      expect(validateWithJsonSchema(schema, 101).success).toBe(false);
    });

    it('should apply exclusiveMinimum constraint', () => {
      const schema: JSONSchema7 = { type: 'number', exclusiveMinimum: 10 };
      
      expect(validateWithJsonSchema(schema, 11).success).toBe(true);
      expect(validateWithJsonSchema(schema, 10).success).toBe(false);
    });

    it('should apply exclusiveMaximum constraint', () => {
      const schema: JSONSchema7 = { type: 'number', exclusiveMaximum: 100 };
      
      expect(validateWithJsonSchema(schema, 99).success).toBe(true);
      expect(validateWithJsonSchema(schema, 100).success).toBe(false);
    });

    it('should apply multipleOf constraint', () => {
      const schema: JSONSchema7 = { type: 'number', multipleOf: 5 };
      
      expect(validateWithJsonSchema(schema, 10).success).toBe(true);
      expect(validateWithJsonSchema(schema, 15).success).toBe(true);
      expect(validateWithJsonSchema(schema, 12).success).toBe(false);
    });
  });

  describe('boolean schemas', () => {
    it('should validate boolean schema', () => {
      const schema: JSONSchema7 = { type: 'boolean' };
      
      expect(validateWithJsonSchema(schema, true).success).toBe(true);
      expect(validateWithJsonSchema(schema, false).success).toBe(true);
      expect(validateWithJsonSchema(schema, 'true').success).toBe(false);
      expect(validateWithJsonSchema(schema, 1).success).toBe(false);
    });
  });

  describe('null schemas', () => {
    it('should validate null schema', () => {
      const schema: JSONSchema7 = { type: 'null' };
      
      expect(validateWithJsonSchema(schema, null).success).toBe(true);
      expect(validateWithJsonSchema(schema, undefined).success).toBe(false);
      expect(validateWithJsonSchema(schema, '').success).toBe(false);
    });
  });

  describe('array schemas', () => {
    it('should validate basic array schema', () => {
      const schema: JSONSchema7 = {
        type: 'array',
        items: { type: 'string' },
      };
      
      expect(validateWithJsonSchema(schema, ['a', 'b', 'c']).success).toBe(true);
      expect(validateWithJsonSchema(schema, []).success).toBe(true);
      expect(validateWithJsonSchema(schema, [1, 2, 3]).success).toBe(false);
      expect(validateWithJsonSchema(schema, 'not-array').success).toBe(false);
    });

    it('should apply minItems constraint', () => {
      const schema: JSONSchema7 = {
        type: 'array',
        items: { type: 'string' },
        minItems: 2,
      };
      
      expect(validateWithJsonSchema(schema, ['a', 'b']).success).toBe(true);
      expect(validateWithJsonSchema(schema, ['a']).success).toBe(false);
      expect(validateWithJsonSchema(schema, []).success).toBe(false);
    });

    it('should apply maxItems constraint', () => {
      const schema: JSONSchema7 = {
        type: 'array',
        items: { type: 'string' },
        maxItems: 3,
      };
      
      expect(validateWithJsonSchema(schema, ['a', 'b', 'c']).success).toBe(true);
      expect(validateWithJsonSchema(schema, ['a', 'b', 'c', 'd']).success).toBe(false);
    });

    it('should handle array without items schema', () => {
      const schema: JSONSchema7 = { type: 'array' };
      
      expect(validateWithJsonSchema(schema, [1, 'two', true]).success).toBe(true);
      expect(validateWithJsonSchema(schema, []).success).toBe(true);
    });

    it('should handle nested arrays', () => {
      const schema: JSONSchema7 = {
        type: 'array',
        items: {
          type: 'array',
          items: { type: 'number' },
        },
      };
      
      expect(validateWithJsonSchema(schema, [[1, 2], [3, 4]]).success).toBe(true);
      expect(validateWithJsonSchema(schema, [[1, 'two']]).success).toBe(false);
    });

    it('should apply uniqueItems constraint', () => {
      const schema: JSONSchema7 = {
        type: 'array',
        items: { type: 'string' },
        uniqueItems: true,
      };
      
      expect(validateWithJsonSchema(schema, ['a', 'b', 'c']).success).toBe(true);
      expect(validateWithJsonSchema(schema, ['a', 'b', 'a']).success).toBe(false);
    });
  });

  describe('object schemas', () => {
    it('should validate basic object schema', () => {
      const schema: JSONSchema7 = {
        type: 'object',
        properties: {
          name: { type: 'string' },
          age: { type: 'number' },
        },
        required: ['name'],
      };
      
      expect(validateWithJsonSchema(schema, { name: 'John' }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { name: 'John', age: 30 }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { age: 30 }).success).toBe(false);
    });

    it('should handle optional properties', () => {
      const schema: JSONSchema7 = {
        type: 'object',
        properties: {
          required: { type: 'string' },
          optional: { type: 'string' },
        },
        required: ['required'],
      };
      
      expect(validateWithJsonSchema(schema, { required: 'value' }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { required: 'value', optional: 'opt' }).success).toBe(true);
      expect(validateWithJsonSchema(schema, {}).success).toBe(false);
    });

    it('should apply default values', () => {
      const schema: JSONSchema7 = {
        type: 'object',
        properties: {
          name: { type: 'string', default: 'Anonymous' },
        },
      };
      
      const result = validateWithJsonSchema(schema, {});
      expect(result.success).toBe(true);
      expect(result.data).toEqual({ name: 'Anonymous' });
    });

    it('should handle nested objects', () => {
      const schema: JSONSchema7 = {
        type: 'object',
        properties: {
          user: {
            type: 'object',
            properties: {
              name: { type: 'string' },
              email: { type: 'string', format: 'email' },
            },
            required: ['name'],
          },
        },
        required: ['user'],
      };
      
      expect(validateWithJsonSchema(schema, { user: { name: 'John' } }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { user: { name: 'John', email: 'john@example.com' } }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { user: {} }).success).toBe(false);
    });

    it('should handle additionalProperties', () => {
      const schema: JSONSchema7 = {
        type: 'object',
        additionalProperties: { type: 'number' },
      };
      
      expect(validateWithJsonSchema(schema, { a: 1, b: 2 }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { a: 'string' }).success).toBe(false);
    });

    it('should handle empty object schema', () => {
      const schema: JSONSchema7 = { type: 'object' };
      
      expect(validateWithJsonSchema(schema, {}).success).toBe(true);
      expect(validateWithJsonSchema(schema, { any: 'value' }).success).toBe(true);
    });
  });

  describe('enum schemas', () => {
    it('should validate string enum', () => {
      const schema: JSONSchema7 = {
        enum: ['red', 'green', 'blue'],
      };
      
      expect(validateWithJsonSchema(schema, 'red').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'green').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'yellow').success).toBe(false);
    });

    it('should validate numeric enum', () => {
      const schema: JSONSchema7 = {
        enum: [1, 2, 3],
      };
      
      expect(validateWithJsonSchema(schema, 1).success).toBe(true);
      expect(validateWithJsonSchema(schema, 2).success).toBe(true);
      expect(validateWithJsonSchema(schema, 4).success).toBe(false);
    });

    it('should validate mixed enum', () => {
      const schema: JSONSchema7 = {
        enum: ['string', 42, true, null],
      };
      
      expect(validateWithJsonSchema(schema, 'string').success).toBe(true);
      expect(validateWithJsonSchema(schema, 42).success).toBe(true);
      expect(validateWithJsonSchema(schema, true).success).toBe(true);
      expect(validateWithJsonSchema(schema, null).success).toBe(true);
      expect(validateWithJsonSchema(schema, false).success).toBe(false);
    });
  });

  describe('const schemas', () => {
    it('should validate string const', () => {
      const schema: JSONSchema7 = { const: 'fixed-value' };
      
      expect(validateWithJsonSchema(schema, 'fixed-value').success).toBe(true);
      expect(validateWithJsonSchema(schema, 'other-value').success).toBe(false);
    });

    it('should validate number const', () => {
      const schema: JSONSchema7 = { const: 42 };
      
      expect(validateWithJsonSchema(schema, 42).success).toBe(true);
      expect(validateWithJsonSchema(schema, 43).success).toBe(false);
    });

    it('should validate boolean const', () => {
      const schema: JSONSchema7 = { const: true };
      
      expect(validateWithJsonSchema(schema, true).success).toBe(true);
      expect(validateWithJsonSchema(schema, false).success).toBe(false);
    });
  });

  describe('union schemas (anyOf)', () => {
    it('should validate anyOf with primitive types', () => {
      const schema: JSONSchema7 = {
        anyOf: [
          { type: 'string' },
          { type: 'number' },
        ],
      };
      
      expect(validateWithJsonSchema(schema, 'hello').success).toBe(true);
      expect(validateWithJsonSchema(schema, 42).success).toBe(true);
      expect(validateWithJsonSchema(schema, true).success).toBe(false);
    });

    it('should validate anyOf with object types', () => {
      const schema: JSONSchema7 = {
        anyOf: [
          {
            type: 'object',
            properties: { type: { const: 'a' }, valueA: { type: 'string' } },
            required: ['type'],
          },
          {
            type: 'object',
            properties: { type: { const: 'b' }, valueB: { type: 'number' } },
            required: ['type'],
          },
        ],
      };
      
      expect(validateWithJsonSchema(schema, { type: 'a', valueA: 'test' }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { type: 'b', valueB: 123 }).success).toBe(true);
    });
  });

  describe('union schemas (oneOf)', () => {
    it('should validate oneOf with primitive types', () => {
      const schema: JSONSchema7 = {
        oneOf: [
          { type: 'string' },
          { type: 'boolean' },
        ],
      };
      
      expect(validateWithJsonSchema(schema, 'hello').success).toBe(true);
      expect(validateWithJsonSchema(schema, true).success).toBe(true);
      expect(validateWithJsonSchema(schema, 42).success).toBe(false);
    });
  });

  describe('intersection schemas (allOf)', () => {
    it('should validate allOf with object types', () => {
      const schema: JSONSchema7 = {
        allOf: [
          {
            type: 'object',
            properties: { name: { type: 'string' } },
            required: ['name'],
          },
          {
            type: 'object',
            properties: { age: { type: 'number' } },
            required: ['age'],
          },
        ],
      };
      
      expect(validateWithJsonSchema(schema, { name: 'John', age: 30 }).success).toBe(true);
      expect(validateWithJsonSchema(schema, { name: 'John' }).success).toBe(false);
      expect(validateWithJsonSchema(schema, { age: 30 }).success).toBe(false);
    });
  });
});

describe('createJsonSchemaValidator', () => {
  it('should create a reusable validator function', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        age: { type: 'number' },
      },
      required: ['name'],
    };

    const validator = createJsonSchemaValidator<{ name: string; age?: number }>(schema);

    const validResult = validator({ name: 'John', age: 30 });
    expect(validResult.success).toBe(true);
    expect(validResult.data).toEqual({ name: 'John', age: 30 });

    const invalidResult = validator({ age: 30 });
    expect(invalidResult.success).toBe(false);
    expect(invalidResult.errors).toBeDefined();
  });

  it('should reuse compiled schema for performance', () => {
    const schema: JSONSchema7 = { type: 'string', minLength: 1 };
    const validator = createJsonSchemaValidator(schema);

    // Multiple calls should use the same compiled schema
    expect(validator('hello').success).toBe(true);
    expect(validator('world').success).toBe(true);
    expect(validator('').success).toBe(false);
  });
});

describe('parseWithJsonSchema', () => {
  it('should parse valid data', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        name: { type: 'string' },
      },
      required: ['name'],
    };

    const result = parseWithJsonSchema<{ name: string }>(schema, { name: 'John' });
    expect(result).toEqual({ name: 'John' });
  });

  it('should throw on invalid data', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        name: { type: 'string' },
      },
      required: ['name'],
    };

    expect(() => parseWithJsonSchema(schema, {})).toThrow('Validation failed');
  });

  it('should include error details in thrown error', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        email: { type: 'string', format: 'email' },
      },
      required: ['email'],
    };

    expect(() => parseWithJsonSchema(schema, { email: 'invalid' })).toThrow();
  });
});

describe('safeParseWithJsonSchema', () => {
  it('should return success result for valid data', () => {
    const schema: JSONSchema7 = { type: 'string', minLength: 1 };

    const result = safeParseWithJsonSchema<string>(schema, 'hello');
    expect(result.success).toBe(true);
    expect(result.data).toBe('hello');
  });

  it('should return error result for invalid data', () => {
    const schema: JSONSchema7 = { type: 'string', minLength: 1 };

    const result = safeParseWithJsonSchema<string>(schema, '');
    expect(result.success).toBe(false);
    expect(result.errors).toBeDefined();
    expect(result.errors!.length).toBeGreaterThan(0);
  });
});

describe('complex schema scenarios', () => {
  it('should handle migration config-like schema', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        sourceClusters: {
          type: 'object',
          additionalProperties: {
            type: 'object',
            properties: {
              endpoint: { type: 'string', format: 'uri' },
              version: { type: 'string', pattern: '^(?:ES|OS) [0-9]+\\.[0-9]+\\.[0-9]+$' },
            },
            required: ['version'],
          },
        },
        targetClusters: {
          type: 'object',
          additionalProperties: {
            type: 'object',
            properties: {
              endpoint: { type: 'string', format: 'uri' },
              version: { type: 'string' },
            },
            required: ['endpoint', 'version'],
          },
        },
        migrationConfigs: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              fromSource: { type: 'string' },
              toTarget: { type: 'string' },
            },
            required: ['fromSource', 'toTarget'],
          },
          minItems: 1,
        },
      },
      required: ['sourceClusters', 'targetClusters', 'migrationConfigs'],
    };

    const validData = {
      sourceClusters: {
        source1: {
          endpoint: 'https://source.example.com',
          version: 'ES 7.10.2',
        },
      },
      targetClusters: {
        target1: {
          endpoint: 'https://target.example.com',
          version: 'OS 2.11.0',
        },
      },
      migrationConfigs: [
        { fromSource: 'source1', toTarget: 'target1' },
      ],
    };

    expect(validateWithJsonSchema(schema, validData).success).toBe(true);

    // Missing required field
    const invalidData = {
      sourceClusters: {},
      targetClusters: {},
      migrationConfigs: [], // minItems: 1 violated
    };

    expect(validateWithJsonSchema(schema, invalidData).success).toBe(false);
  });

  it('should handle auth config union schema', () => {
    const schema: JSONSchema7 = {
      anyOf: [
        {
          type: 'object',
          properties: {
            basic: {
              type: 'object',
              properties: {
                secretName: { type: 'string' },
              },
              required: ['secretName'],
            },
          },
          required: ['basic'],
        },
        {
          type: 'object',
          properties: {
            sigv4: {
              type: 'object',
              properties: {
                region: { type: 'string' },
                service: { type: 'string', default: 'es' },
              },
              required: ['region'],
            },
          },
          required: ['sigv4'],
        },
      ],
    };

    expect(validateWithJsonSchema(schema, { basic: { secretName: 'my-secret' } }).success).toBe(true);
    expect(validateWithJsonSchema(schema, { sigv4: { region: 'us-east-1' } }).success).toBe(true);
    expect(validateWithJsonSchema(schema, { invalid: {} }).success).toBe(false);
  });

  it('should handle deeply nested schemas', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        level1: {
          type: 'object',
          properties: {
            level2: {
              type: 'object',
              properties: {
                level3: {
                  type: 'object',
                  properties: {
                    value: { type: 'string' },
                  },
                  required: ['value'],
                },
              },
              required: ['level3'],
            },
          },
          required: ['level2'],
        },
      },
      required: ['level1'],
    };

    const validData = {
      level1: {
        level2: {
          level3: {
            value: 'deep',
          },
        },
      },
    };

    expect(validateWithJsonSchema(schema, validData).success).toBe(true);
  });
});

describe('Ajv-specific features', () => {
  it('should support $ref resolution', () => {
    const schema: JSONSchema7 = {
      definitions: {
        address: {
          type: 'object',
          properties: {
            street: { type: 'string' },
            city: { type: 'string' },
          },
          required: ['street', 'city'],
        },
      },
      type: 'object',
      properties: {
        home: { $ref: '#/definitions/address' },
        work: { $ref: '#/definitions/address' },
      },
    };

    const validData = {
      home: { street: '123 Main St', city: 'Springfield' },
      work: { street: '456 Office Blvd', city: 'Metropolis' },
    };

    expect(validateWithJsonSchema(schema, validData).success).toBe(true);
  });

  it('should support conditional schemas (if/then/else)', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        country: { type: 'string' },
        postalCode: { type: 'string' },
      },
      if: {
        properties: { country: { const: 'US' } },
      },
      then: {
        properties: { postalCode: { pattern: '^[0-9]{5}$' } },
      },
      else: {
        properties: { postalCode: { pattern: '^[A-Z0-9]+$' } },
      },
    };

    // US postal code
    expect(validateWithJsonSchema(schema, { country: 'US', postalCode: '12345' }).success).toBe(true);
    expect(validateWithJsonSchema(schema, { country: 'US', postalCode: 'ABC123' }).success).toBe(false);
    
    // Non-US postal code
    expect(validateWithJsonSchema(schema, { country: 'CA', postalCode: 'K1A0B1' }).success).toBe(true);
  });

  it('should support patternProperties', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      patternProperties: {
        '^S_': { type: 'string' },
        '^I_': { type: 'integer' },
      },
    };

    expect(validateWithJsonSchema(schema, { S_name: 'John', I_age: 30 }).success).toBe(true);
    expect(validateWithJsonSchema(schema, { S_name: 123 }).success).toBe(false);
    expect(validateWithJsonSchema(schema, { I_age: 'thirty' }).success).toBe(false);
  });

  it('should support dependencies keyword', () => {
    const schema: JSONSchema7 = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        creditCard: { type: 'string' },
        billingAddress: { type: 'string' },
      },
      dependencies: {
        creditCard: ['billingAddress'],
      },
    };

    expect(validateWithJsonSchema(schema, { name: 'John' }).success).toBe(true);
    expect(validateWithJsonSchema(schema, { name: 'John', creditCard: '1234', billingAddress: '123 Main' }).success).toBe(true);
    expect(validateWithJsonSchema(schema, { name: 'John', creditCard: '1234' }).success).toBe(false);
  });
});
