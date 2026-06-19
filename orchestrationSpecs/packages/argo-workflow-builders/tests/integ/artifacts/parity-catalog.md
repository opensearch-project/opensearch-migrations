# Argo Workflow Builder Catalog
Generated: 2026-06-19T15:05:32.662Z

## Summary
- Total Test Cases: 18
- Full Parity: 6 ✅
- Partial Parity: 0 ⚠️
- Contract Only: 12 ⚠️
- Broken Parity: 0 ❌
- Known Broken (Skipped): 0 🚧
- Total Builder Variants: 6
- Errors Expected: 1

- Report Mode: default (broken tests skipped)

## Regex And Advanced

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| asFloat on integer string | `string(asFloat(inputs.parameters.value))` | `{"value":"42"}` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| asInt on decimal string errors | `string(asInt(inputs.parameters.value))` | `{"value":"42.7"}` | Error | ✅ Error | ⚠️ No builder support | ⚠️ |
| bracket notation array access | `fromJSON(inputs.parameters.data)[1]` | `{"data":"[\\"a\\",\\"b\\",\\"c\\"]"}` | `"b"` | ✅ `"b"` | ⚠️ No builder support | ⚠️ |
| bracket notation map access | `fromJSON(inputs.parameters.data)['my-key']` | `{"data":"{\\"my-key\\":\\"value\\"}"}` | `"value"` | ✅ `"value"` | ⚠️ No builder support | ⚠️ |
| bracket notation negative index | `fromJSON(inputs.parameters.data)[-1]` | `{"data":"[\\"a\\",\\"b\\",\\"c\\"]"}` | `"c"` | ✅ `"c"` | ⚠️ No builder support | ⚠️ |
| filter with bracket notation | `toJson(filter(fromJSON(inputs.parameters.data), <wbr>{#['age'] > 26}))` | `{"data":"[{\\"name\\":\\"John\\",\\"age\\":30},{<wbr>\\"name\\":\\"Jane\\",\\"age\\":25}]"}` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| in operator array membership false | `string(inputs.parameters.value in ['John', 'Jane<wbr>', 'Bob'])` | `{"value":"Alice"}` | `"false"` | ✅ `"false"` | ⚠️ No builder support | ⚠️ |
| in operator array membership true | `string(inputs.parameters.value in ['John', 'Jane<wbr>', 'Bob'])` | `{"value":"John"}` | `"true"` | ✅ `"true"` | ⚠️ No builder support | ⚠️ |
| in operator map key false | `string('email' in fromJSON(inputs.parameters.dat<wbr>a))` | `{"data":"{\\"name\\":\\"John\\",\\"age\\":30}"}` | `"false"` | ✅ `"false"` | ✅ **hasKey**: `expr.toString(expr.hasKey(expr.deserializeRecord<wbr>(ctx.inputs.data), "email"))` → `"false"` | ✅ |
| in operator map key true | `string('name' in fromJSON(inputs.parameters.data<wbr>))` | `{"data":"{\\"name\\":\\"John\\",\\"age\\":30}"}` | `"true"` | ✅ `"true"` | ✅ **hasKey**: `expr.toString(expr.hasKey(expr.deserializeRecord<wbr>(ctx.inputs.data), "name"))` → `"true"` | ✅ |
| map with bracket notation | `toJson(map(fromJSON(inputs.parameters.data), {#[<wbr>'value'] * 2}))` | `{"data":"[{\\"value\\":1},{\\"value\\":2},{\\"va<wbr>lue\\":3}]"}` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| regexFindAll find all matches | `toJson(sprig.regexFindAll('[2,4,6,8]', inputs.pa<wbr>rameters.text, -1))` | `{"text":"123456789"}` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| regexFind extract pattern | `sprig.regexFind('[a-zA-Z][1-9]', inputs.paramete<wbr>rs.text)` | `{"text":"abcd1234"}` | `"d1"` | ✅ `"d1"` | ✅ **regexFind**: `expr.regexFind(expr.literal('[a-zA-Z][1-9]'), ct<wbr>x.inputs.text)` → `"d1"` | ✅ |
| regexMatch invalid email | `string(sprig.regexMatch('^[A-Za-z0-9._%+-]+@[A-Z<wbr>a-z0-9.-]+\\\\.[A-Za-z]{2,}$', inputs.parameters<wbr>.email))` | `{"email":"not-an-email"}` | `"false"` | ✅ `"false"` | ✅ **regexMatch**: `expr.toString(expr.regexMatch(expr.literal('^[A-<wbr>Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$'<wbr>), ctx.inputs.email))` → `"false"` | ✅ |
| regexMatch valid email | `string(sprig.regexMatch('^[A-Za-z0-9._%+-]+@[A-Z<wbr>a-z0-9.-]+\\\\.[A-Za-z]{2,}$', inputs.parameters<wbr>.email))` | `{"email":"test@example.com"}` | `"true"` | ✅ `"true"` | ✅ **regexMatch**: `expr.toString(expr.regexMatch(expr.literal('^[A-<wbr>Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$'<wbr>), ctx.inputs.email))` → `"true"` | ✅ |
| regexReplaceAll capture groups | `sprig.regexReplaceAll('a(x*)b', inputs.parameter<wbr>s.text, '${1}W')` | `{"text":"-ab-axxb-"}` | `"-W-xxW-"` | ✅ `"-W-xxW-"` | ✅ **regexReplaceAll**: `expr.regexReplaceAll(expr.literal('a(x*)b'), exp<wbr>r.literal('${1}W'), ctx.inputs.text)` → `"-W-xxW-"` | ✅ |
| regexSplit by pattern | `toJson(sprig.regexSplit('z+', inputs.parameters.<wbr>text, -1))` | `{"text":"pizza"}` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| ternary with in operator | `inputs.parameters.role in ['admin', 'superuser']<wbr> ? 'allowed' : 'denied'` | `{"role":"admin"}` | `"allowed"` | ✅ `"allowed"` | ⚠️ No builder support | ⚠️ |

## Legend
- ✅ Pass — test passed, result matches expected
- ❌ Fail — test failed or result doesn't match
- ⚠️ No builder support — Argo feature has no builder API equivalent
- ⚠️ Partial — some builder variants pass, others fail
- \- Not tested
