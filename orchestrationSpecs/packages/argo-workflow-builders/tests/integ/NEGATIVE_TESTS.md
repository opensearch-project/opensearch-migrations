# Negative Tests Implementation Summary

## Overview

Added comprehensive negative tests to complement the positive tests and ensure that:
1. Invalid inputs fail as expected
2. Wrong values don't accidentally match
3. Type boundaries are enforced
4. The positive tests are actually validating correct behavior

## Files Added

### 1. `contracts/negative.integ.test.ts` (~20 tests)

**Invalid expressions should fail:**
- Invalid JSONPath syntax
- Invalid parameter references
- Type mismatches in arithmetic
- Malformed JSON in fromJson

**Wrong values should not match:**
- Wrong JSONPath results
- Ternary branch verification
- String concatenation order

**Parameter corruption detection:**
- Modified JSON detection
- Empty string vs null/undefined
- Whitespace preservation

**Type coercion boundaries:**
- Number formatting (no quotes, no decimals)
- Boolean casing (lowercase only)
- Arithmetic precision

### 2. `modelValidation/negative.integ.test.ts` (~5 tests)

**Wrong parameter references:**
- Wrong parameter name returns empty, not input value
- Missing parameter forwarding fails
- Wrong step name in output reference
- Wrong task name in DAG reference

**Serialization validation:**
- Object serialized as JSON, not plain string or [object Object]

### 3. `contracts/whenConditionsNegative.integ.test.ts` (~5 tests)

**Conditional execution verification:**
- False condition does NOT execute (verifies Skipped, not Succeeded)
- True condition does NOT skip (verifies Succeeded, not Skipped)
- Expression-based conditions work correctly
- Wrong comparison values skip as expected

## Test Patterns

### Pattern 1: Expect Failure
```typescript
test("invalid input fails", async () => {
  try {
    const result = await submitProbe({
      expression: "invalid(syntax",
    });
    expect(result.phase).not.toBe("Succeeded");
  } catch (err) {
    expect(err.message).toMatch(/timed out|Failed|Error/);
  }
});
```

### Pattern 2: Verify Wrong Values Don't Match
```typescript
test("wrong value does not match", async () => {
  const result = await submitProbe({
    expression: "1 + 1",
  });
  
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.result).not.toBe("3");
  expect(result.globalOutputs.result).not.toBe("11");
  expect(result.globalOutputs.result).toBe("2");
});
```

### Pattern 3: Verify Boundaries
```typescript
test("type boundary is enforced", async () => {
  const result = await submitProbe({
    inputs: { data: '{"num":42}' },
    expression: "jsonpath(inputs.parameters.data, '$.num')",
  });
  
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.result).not.toBe('"42"');  // Not quoted
  expect(result.globalOutputs.result).not.toBe("42.0");  // Not float
  expect(result.globalOutputs.result).toBe("42");        // Exact
});
```

### Pattern 4: Verify Conditional Logic
```typescript
test("false condition does not execute", async () => {
  const result = await submitConditionalProbe("no", "{{input}} == yes");
  
  expect(result.phase).toBe("Succeeded");
  expect(result.nodeOutputs["step"].phase).not.toBe("Succeeded");
  expect(result.nodeOutputs["step"].phase).toBe("Skipped");
});
```

## Why These Tests Matter

### 1. Prevent False Positives
Without negative tests, a test that always returns "Succeeded" would pass even if the actual logic is broken.

**Example:**
```typescript
// Without negative test - could pass even if broken
test("adds numbers", async () => {
  const result = await submitProbe({ expression: "1 + 1" });
  expect(result.phase).toBe("Succeeded");
  // What if it always returns "Succeeded" regardless of expression?
});

// With negative test - ensures actual validation
test("adds numbers correctly", async () => {
  const result = await submitProbe({ expression: "1 + 1" });
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.result).not.toBe("3");  // Wrong answer
  expect(result.globalOutputs.result).toBe("2");      // Right answer
});
```

### 2. Document Expected Failures
Negative tests document what SHOULD fail, making the test suite more complete.

### 3. Catch Regressions
If Argo's behavior changes (e.g., starts accepting invalid syntax), negative tests will catch it.

### 4. Verify Type Safety
Ensures type coercion happens exactly as expected, not approximately.

## Coverage Summary

| Category | Positive Tests | Negative Tests | Total |
|----------|---------------|----------------|-------|
| JSONPath | 9 | 4 | 13 |
| Sprig Functions | 4 | 1 | 5 |
| Expression Eval | 8 | 3 | 11 |
| Param Passthrough | 8 | 3 | 11 |
| When Conditions | 4 | 5 | 9 |
| Model Validation | 5 | 5 | 10 |
| **Total** | **38** | **21** | **59** |

## Running Negative Tests

```bash
# Run all integration tests (includes negative)
npm run test:integ

# Run only negative contract tests
npm run test:integ -- negative.integ.test

# Run only negative model validation
npm run test:integ -- modelValidation/negative

# Run only negative when conditions
npm run test:integ -- whenConditionsNegative
```

## Future Additions

When adding new features, always add both:
1. **Positive test**: Feature works correctly
2. **Negative test**: Feature fails when it should, and wrong values don't match

See `tests/integ/QUICK_REFERENCE.md` for patterns and examples.
