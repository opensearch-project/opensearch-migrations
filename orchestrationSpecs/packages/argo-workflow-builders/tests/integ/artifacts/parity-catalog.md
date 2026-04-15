# Argo Workflow Builder Catalog
Generated: 2026-04-10T21:28:19.261Z

## Summary
- Total Test Cases: 54
- Full Parity: 27 ✅
- Partial Parity: 0 ⚠️
- Contract Only: 24 ⚠️
- Broken Parity: 0 ❌
- Known Broken (Skipped): 0 🚧
- Total Builder Variants: 29
- Errors Expected: 5

- Report Mode: default (broken tests skipped)

## Array Operations

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| array indexing with literal index | `fromJSON(inputs.parameters.arr)[1]` | `{"arr":"[\\"a\\",\\"b\\",\\"c\\"]"}` | `"b"` | ✅ `"b"` | ✅ **index**: `expr.index(expr.deserializeRecord(ctx.inputs.arr<wbr>), expr.literal(1))` → `"b"` | ✅ |
| array length | `string(len(fromJSON(inputs.parameters.arr)))` | `{"arr":"[\\"a\\",\\"b\\",\\"c\\"]"}` | `"3"` | ✅ `"3"` | ✅ **length**: `expr.toString(expr.length(expr.deserializeRecord<wbr>(ctx.inputs.arr)))` → `"3"` | ✅ |
| array with mixed types | `toJson(fromJSON(inputs.parameters.arr))` | `{"arr":"[1,\\"two\\",true]"}` | `"[1,"two",true]"` | ✅ `"[1,"two",true]"` | ✅ **serialize**: `expr.serialize(expr.deserializeRecord(ctx.inputs<wbr>.arr))` → `"[<br>  1,<br>  "two",<br>  true<br>]"` | ✅ |
| empty array length | `string(len(fromJSON(inputs.parameters.arr)))` | `{"arr":"[]"}` | `"0"` | ✅ `"0"` | ✅ **length**: `expr.toString(expr.length(expr.deserializeRecord<wbr>(ctx.inputs.arr)))` → `"0"` | ✅ |
| last element of array | `last(fromJSON(inputs.parameters.arr))` | `{"arr":"[\\"first\\",\\"middle\\",\\"last\\"]"}` | `"last"` | ✅ `"last"` | ✅ **last**: `expr.last(expr.deserializeRecord(ctx.inputs.arr)<wbr>)` → `"last"` | ✅ |
| nested array access | `string(jsonpath(inputs.parameters.data, '$.items<wbr>[1][0]'))` | `{"data":"{\\"items\\":[[1,2],[3,4]]}"}` | `"3"` | ✅ `"3"` | ⚠️ No builder support | ⚠️ |

## Expression Evaluation

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| asFloat | `string(asFloat(inputs.parameters.x))` | `{"x":"3.14"}` | `"3.14"` | ✅ `"3.14"` | ✅ **cast**: `expr.toString(expr.cast(ctx.inputs.x).to<number><wbr>())` → `"3.14"` | ✅ |
| asInt then arithmetic | `string(asInt(inputs.parameters.x) * 2)` | `{"x":"10"}` | `"20"` | ✅ `"20"` | ⚠️ No builder support | ⚠️ |
| boolean expressions | `true ? 'yes' : 'no'` | `{}` | `"yes"` | ✅ `"yes"` | ✅ **ternary**: `expr.ternary(expr.literal(true), expr.literal("y<wbr>es"), expr.literal("no"))` → `"yes"` | ✅ |
| nested ternary | `asInt(inputs.parameters.x) > 10 ? 'big' : asInt(<wbr>inputs.parameters.x) > 3 ? 'medium' : 'small'` | `{"x":"5"}` | `"medium"` | ✅ `"medium"` | ✅ **ternary**: `expr.ternary(expr.greaterThan(expr.deserializeRe<wbr>cord(ctx.inputs.x), expr.literal(10)), expr.lite<wbr>ral("big"), expr.ternar…` → `"medium"` | ✅ |
| string concatenation | `inputs.parameters.a + ' ' + inputs.parameters.b` | `{"a":"hello","b":"world"}` | `"hello world"` | ✅ `"hello world"` | ✅ **concat**: `expr.concat(ctx.inputs.a, expr.literal(" "), ctx<wbr>.inputs.b)` → `"hello world"` | ✅ |
| string equality | `inputs.parameters.status == 'ready' ? 'go' : 'wa<wbr>it'` | `{"status":"ready"}` | `"go"` | ✅ `"go"` | ✅ **ternary/equals**: `expr.ternary(expr.equals(ctx.inputs.status, expr<wbr>.literal("ready")), expr.literal("go"), expr.lit<wbr>eral("wait"))` → `"go"` | ✅ |
| ternary false branch | `asInt(inputs.parameters.count) > 3 ? 'high' : 'l<wbr>ow'` | `{"count":"1"}` | `"low"` | ✅ `"low"` | ✅ **ternary**: `expr.ternary(expr.greaterThan(expr.deserializeRe<wbr>cord(ctx.inputs.count), expr.literal(3)), expr.l<wbr>iteral("high"), expr.li…` → `"low"` | ✅ |
| ternary true branch | `asInt(inputs.parameters.count) > 3 ? 'high' : 'l<wbr>ow'` | `{"count":"5"}` | `"high"` | ✅ `"high"` | ✅ **ternary**: `expr.ternary(expr.greaterThan(expr.deserializeRe<wbr>cord(ctx.inputs.count), expr.literal(3)), expr.l<wbr>iteral("high"), expr.li…` → `"high"` | ✅ |

## Expression Utilities

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| fillTemplate | `inputs.parameters.first + '-' + inputs.parameter<wbr>s.second` | `{"first":"alpha","second":"beta"}` | `"alpha-beta"` | - | 🚧 **fillTemplate** (skipped by default): Runtime phase Error: fillTemplate builder output does not execute successfully in Argo. | - |
| taskData steps output reference | `steps.produce.outputs.parameters.value` | - | `"hello"` | - | 🚧 **taskData** (skipped by default): Runtime Failed: taskData reference rendering is not yet compatible for this steps output access. | - |

## JSONPath

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| array index extraction | `jsonpath(inputs.parameters.data, '$.items[1]')` | `{"data":"{\\"items\\":[\\"a\\",\\"b\\",\\"c\\"]}<wbr>"}` | `"b"` | ✅ `"b"` | ⚠️ No builder support | ⚠️ |
| extract array as JSON | `jsonpath(inputs.parameters.data, '$.items')` | `{"data":"{\\"items\\":[1,2,3]}"}` | `"[1 2 3]"` | ✅ `"[1 2 3]"` | ⚠️ No builder support | ⚠️ |
| extract boolean as lowercase | `jsonpath(inputs.parameters.data, '$.flag')` | `{"data":"{\\"flag\\":true}"}` | `"true"` | ✅ `"true"` | ⚠️ No builder support | ⚠️ |
| extract nested object as JSON | `jsonpath(inputs.parameters.data, '$.outer')` | `{"data":"{\\"outer\\":{\\"inner\\":\\"val\\"}}"}<wbr>` | `"map[inner:val]"` | ✅ `"map[inner:val]"` | ⚠️ No builder support | ⚠️ |
| extract null | `jsonpath(inputs.parameters.data, '$.key')` | `{"data":"{\\"key\\":null}"}` | `"<nil>"` | ✅ `"<nil>"` | ⚠️ No builder support | ⚠️ |
| extract number as bare string | `jsonpath(inputs.parameters.data, '$.key')` | `{"data":"{\\"key\\":1}"}` | `"1"` | ✅ `"1"` | ✅ **jsonPathStrict**: `expr.jsonPathStrict(ctx.inputs.data, "key")` → `"1"`<br>✅ **jsonPathStrictSerialized**: `expr.jsonPathStrictSerialized(ctx.inputs.data, "<wbr>key")` → `"1"` | ✅ |
| extract string without extra quotes | `jsonpath(inputs.parameters.data, '$.key')` | `{"data":"{\\"key\\":\\"hello\\"}"}` | `"hello"` | ✅ `"hello"` | ✅ **jsonPathStrict**: `expr.jsonPathStrict(ctx.inputs.data, "key")` → `"hello"` | ✅ |
| missing path causes Error | `jsonpath(inputs.parameters.data, '$.missing')` | `{"data":"{\\"key\\":\\"val\\"}"}` | Error | ✅ Error | ⚠️ No builder support | ⚠️ |
| nested path extraction | `jsonpath(inputs.parameters.data, '$.a.b.c')` | `{"data":"{\\"a\\":{\\"b\\":{\\"c\\":\\"deep\\"}}<wbr>}"}` | `"deep"` | ✅ `"deep"` | ⚠️ No builder support | ⚠️ |

## Logical Operations

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| == with numbers | `string(asInt(inputs.parameters.a) == asInt(input<wbr>s.parameters.b))` | `{"a":"5","b":"5"}` | `"true"` | ✅ `"true"` | ✅ **equals**: `expr.toString(expr.equals(expr.deserializeRecord<wbr>(ctx.inputs.a), expr.deserializeRecord(ctx.input<wbr>s.b)))` → `"true"` | ✅ |
| complex logical expression | `string((asInt(inputs.parameters.a) > asInt(input<wbr>s.parameters.c)) && (asInt(inputs.parameters.b) <wbr>> asInt(inputs.paramete…` | `{"a":"5","b":"10","c":"3"}` | `"true"` | ✅ `"true"` | ✅ **and/greaterThan**: `expr.toString(expr.and(expr.greaterThan(expr.des<wbr>erializeRecord(ctx.inputs.a), expr.deserializeRe<wbr>cord(ctx.inputs.c)), ex…` → `"true"` | ✅ |
| greater than | `string(asInt(inputs.parameters.a) > asInt(inputs<wbr>.parameters.b))` | `{"a":"10","b":"5"}` | `"true"` | ✅ `"true"` | ✅ **greaterThan**: `expr.toString(expr.greaterThan(expr.deserializeR<wbr>ecord(ctx.inputs.a), expr.deserializeRecord(ctx.<wbr>inputs.b)))` → `"true"` | ✅ |
| less than | `string(asInt(inputs.parameters.a) < asInt(inputs<wbr>.parameters.b))` | `{"a":"3","b":"5"}` | `"true"` | ✅ `"true"` | ✅ **lessThan**: `expr.toString(expr.lessThan(expr.deserializeReco<wbr>rd(ctx.inputs.a), expr.deserializeRecord(ctx.inp<wbr>uts.b)))` → `"true"` | ✅ |
| logical and true | `string(true && true)` | - | `"true"` | ✅ `"true"` | ✅ **and**: `expr.toString(expr.and(expr.literal(true), expr.<wbr>literal(true)))` → `"true"` | ✅ |
| logical not true | `string(!true)` | - | `"false"` | ✅ `"false"` | ✅ **not**: `expr.toString(expr.not(expr.literal(true)))` → `"false"` | ✅ |
| logical or true | `string(false \|\| true)` | - | `"true"` | ✅ `"true"` | ✅ **or**: `expr.toString(expr.or(expr.literal(false), expr.<wbr>literal(true)))` → `"true"` | ✅ |

## Negative

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| arithmetic result exact | `string(asInt(inputs.parameters.x) * 2)` | `{"x":"10"}` | `"20"` | ✅ `"20"` | ⚠️ No builder support | ⚠️ |
| boolean lowercase not capitalized | `jsonpath(inputs.parameters.data, '$.flag')` | `{"data":"{\\"flag\\":true}"}` | `"true"` | ✅ `"true"` | ⚠️ No builder support | ⚠️ |
| empty string not null or undefined | `inputs.parameters.input (chain passthrough)` | `{"input":""}` | - | ✅ `""` | ⚠️ No builder support | ⚠️ |
| invalid JSONPath syntax fails | `jsonpath(inputs.parameters.data, 'invalid[[[synt<wbr>ax')` | `{"data":"{\\"key\\":\\"value\\"}"}` | Error | ✅ Error | ⚠️ No builder support | ⚠️ |
| invalid function name fails | `invalidFunction(inputs.parameters.data)` | `{"data":"{\\"key\\":\\"value\\"}"}` | Error | ✅ Error | ⚠️ No builder support | ⚠️ |
| invalid parameter reference returns nil | `inputs.parameters.nonexistent` | `{"data":"test"}` | `"<nil>"` | ✅ `"<nil>"` | ⚠️ No builder support | ⚠️ |
| malformed JSON fromJSON fails | `fromJSON(inputs.parameters.data)` | `{"data":"{invalid json}"}` | Error | ✅ Error | ⚠️ No builder support | ⚠️ |
| modified JSON detected | `inputs.parameters.input (chain passthrough)` | `{"input":"{\\"a\\":1,\\"b\\":\\"two\\"}"}` | `"{"a":1,"b":"two"}"` | ✅ `"{"a":1,"b":"two"}"` | ⚠️ No builder support | ⚠️ |
| number not stringified with quotes | `jsonpath(inputs.parameters.data, '$.num')` | `{"data":"{\\"num\\":42}"}` | `"42"` | ✅ `"42"` | ⚠️ No builder support | ⚠️ |
| string concatenation order matters | `inputs.parameters.a + ' ' + inputs.parameters.b` | `{"a":"hello","b":"world"}` | `"hello world"` | ✅ `"hello world"` | ⚠️ No builder support | ⚠️ |
| ternary evaluates correct branch | `asInt(inputs.parameters.count) > 3 ? 'high' : 'l<wbr>ow'` | `{"count":"5"}` | `"high"` | ✅ `"high"` | ⚠️ No builder support | ⚠️ |
| type mismatch in arithmetic fails | `asInt(inputs.parameters.text) * 2` | `{"text":"not-a-number"}` | Error | ✅ Error | ⚠️ No builder support | ⚠️ |
| whitespace preserved exactly | `inputs.parameters.input (chain passthrough)` | `{"input":"  "}` | `"  "` | ✅ `"  "` | ⚠️ No builder support | ⚠️ |
| wrong JSONPath result does not match | `jsonpath(inputs.parameters.data, '$.key')` | `{"data":"{\\"key\\":\\"value\\"}"}` | `"value"` | ✅ `"value"` | ⚠️ No builder support | ⚠️ |

## Resource Conditions

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| failureCondition expression | `{{="data.state == '" + inputs.parameters.blocked<wbr>State + "'"}}` | `{"blockedState":"blocked"}` | - | ❌ `"null"` (Failed)<br><sub>expected phase Succeeded, got Failed</sub><br><sub>message: child 'parity-resource-failure-c5j4q-3744323228' failed</sub> | ⚠️ No builder support | ⚠️ |

## Type Coercion

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| base64 round-trip preserves data | `fromBase64(toBase64(inputs.parameters.text))` | `{"text":"test data 123"}` | `"test data 123"` | ✅ `"test data 123"` | ✅ **fromBase64/toBase64**: `expr.fromBase64(expr.toBase64(ctx.inputs.text))` → `"test data 123"` | ✅ |
| fromBase64 decodes string | `fromBase64(inputs.parameters.encoded)` | `{"encoded":"aGVsbG8="}` | `"hello"` | - | ✅ **fromBase64**: `expr.fromBase64(ctx.inputs.encoded)` → `"hello"` | - |

## When Conditions

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| expression false not succeeded | `{{= asInt(workflow.parameters.input) > 3 }}` | `{"input":"1"}` | - | ✅ (Succeeded) | ✅ **when numeric comparison**: `addStep(..., { when: { templateExp: expr.greater<wbr>Than(expr.deserializeRecord(ctx.inputs.input), e<wbr>xpr.literal(3)) } })` → (Succeeded) | ✅ |
| expression true not skipped | `{{= asInt(workflow.parameters.input) > 3 }}` | `{"input":"5"}` | - | ✅ (Succeeded) | ✅ **when numeric comparison**: `addStep(..., { when: { templateExp: expr.greater<wbr>Than(expr.deserializeRecord(ctx.inputs.input), e<wbr>xpr.literal(3)) } })` → (Succeeded) | ✅ |
| false condition not succeeded | `{{workflow.parameters.input}} == yes` | `{"input":"no"}` | - | ✅ (Succeeded) | ✅ **when equals**: `addStep(..., { when: { templateExp: expr.equals(<wbr>expr.length(ctx.inputs.input), expr.literal(3)) <wbr>} })` → (Succeeded) | ✅ |
| true condition not skipped | `{{workflow.parameters.input}} == yes` | `{"input":"yes"}` | - | ✅ (Succeeded) | ✅ **when equals**: `addStep(..., { when: { templateExp: expr.equals(<wbr>expr.length(ctx.inputs.input), expr.literal(3)) <wbr>} })` → (Succeeded) | ✅ |
| wrong comparison value skips | `{{workflow.parameters.input}} == yes` | `{"input":"maybe"}` | - | ✅ (Succeeded) | ✅ **when equals**: `addStep(..., { when: { templateExp: expr.equals(<wbr>expr.length(ctx.inputs.input), expr.literal(3)) <wbr>} })` → (Succeeded) | ✅ |

## Legend
- ✅ Pass — test passed, result matches expected
- ❌ Fail — test failed or result doesn't match
- ⚠️ No builder support — Argo feature has no builder API equivalent
- ⚠️ Partial — some builder variants pass, others fail
- \- Not tested
