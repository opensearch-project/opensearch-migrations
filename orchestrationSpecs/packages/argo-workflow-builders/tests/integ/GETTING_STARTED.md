# Integration Tests - Getting Started

## Quick Start

```bash
# Run all integration tests
npm run test:integ

# Run only contract tests (recommended)
npm run test:integ -- contracts/

# Run specific test
npm run test:integ -- jsonpath.integ.test.ts
```

## What Are These Tests?

Integration tests that validate Argo Workflows expression behavior by:
1. Starting a real K3s cluster with Argo installed
2. Submitting workflows with various expressions
3. Verifying the actual runtime output

**Current Status**: ✅ All 154 tests passing (~80 seconds runtime)

## Test Categories

### Contract Tests (`contracts/`)
Document Argo's actual behavior for:
- JSONPath extraction
- Sprig functions (regex, merge, omit, dig, keys)
- Expression evaluation
- Type conversion
- Operators (in, bracket notation)

### Model Validation Tests (`modelValidation/`)
Verify the builder API produces correct workflows (currently being updated).

## Key Features Verified

✅ **Regex** - All Sprig regex functions work  
✅ **Advanced Sprig** - merge, omit, dig, keys  
✅ **Bracket notation** - `obj['key']`, `array[1]`, `array[-1]`  
✅ **'in' operator** - Array/map membership checks  
✅ **Type conversion** - asInt, asFloat, string

## Important Findings

### asInt() Behavior
```typescript
asInt("42")     // ✅ Works
asInt("42.7")   // ❌ Errors (doesn't truncate)

// Use this for decimals:
int(asFloat("42.7"))  // ✅ Works → 42
```

### Regex Escaping
```typescript
// Need double backslashes:
sprig.regexMatch('\\d+', text)
```

### JSONPath Type Coercion
- Numbers: `"42"` (string, no quotes)
- Booleans: `"true"` (lowercase)
- Objects/Arrays: JSON strings
- Null: `"null"` (string)

## Documentation

- [README.md](README.md) - Full documentation
- [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - Code examples
- `artifacts/parity-catalog.md` - Generated after running tests (see `npm run test:integ`)

## Prerequisites

- Docker installed and running
- Docker must support privileged containers (for K3s)
- Node.js and npm

## First Run

The first run will:
1. Pull K3s container image (~200MB) - takes 1-2 minutes
2. Start K3s and install Argo - takes 15-30 seconds
3. Run tests - takes ~80 seconds

Subsequent runs are faster (images cached).

## Need Help?

File an issue or check the test output in `artifacts/parity-catalog.md` for diagnostics.
