# Argo Workflow Builder Catalog
Generated: 2026-03-01T15:19:16.764Z

## Summary
- Total Test Cases: 1
- Full Parity: 1 ✅
- Partial Parity: 0 ⚠️
- Contract Only: 0 ⚠️
- Broken Parity: 0 ❌
- Known Broken (Skipped): 0 🚧
- Total Builder Variants: 1
- Errors Expected: 0

- Report Mode: default (broken tests skipped)

## Loop Item

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| withParam from JSON array | `withParam: {{workflow.parameters.items}}` | `{"items":"[\\"one\\",\\"two\\",\\"three\\"]"}` | - | ✅ (Succeeded) | ✅ **loopWith param**: `addStep(..., c => c.register({ value: expr.asStr<wbr>ing(c.item) }), { loopWith: makeParameterLoop(ct<wbr>x.inputs.items) })` → (Succeeded) | ✅ |

## Legend
- ✅ Pass — test passed, result matches expected
- ❌ Fail — test failed or result doesn't match
- ⚠️ No builder support — Argo feature has no builder API equivalent
- ⚠️ Partial — some builder variants pass, others fail
- \- Not tested
