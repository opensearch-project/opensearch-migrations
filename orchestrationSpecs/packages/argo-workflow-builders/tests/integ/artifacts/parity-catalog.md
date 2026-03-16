# Argo Workflow Builder Catalog
Generated: 2026-03-01T21:23:34.288Z

## Summary
- Total Test Cases: 11
- Full Parity: 9 ✅
- Partial Parity: 0 ⚠️
- Contract Only: 2 ⚠️
- Broken Parity: 0 ❌
- Known Broken (Skipped): 0 🚧
- Total Builder Variants: 9
- Errors Expected: 0

- Report Mode: default (broken tests skipped)

## Loop Item

| Test Case | Argo Expression | Inputs | Expected | Argo | Builder Variants | Parity |
|-----------|----------------|--------|----------|------|------------------|--------|
| item number coerced with string() | `{{='value-' + string(item)}}` | - | - | ✅ (Succeeded) | ✅ **item number coercion**: `addStep(..., c => c.register({ computed: expr.co<wbr>ncat(expr.literal('value-'), c.item) }), { loopW<wbr>ith: makeItemsLoop([10,…` → (Succeeded) | ✅ |
| item used in expression directly | `{{=item + '-processed'}}` | - | - | ✅ (Succeeded) | ✅ **item expression**: `addStep(..., c => c.register({ computed: expr.co<wbr>ncat(c.item, expr.literal('-processed')) }), { l<wbr>oopWith: makeItemsLoop(…` → (Succeeded) | ✅ |
| nested loop over sub-array extracted from parameter | `withParam: toJson(fromJson(inputs.parameters.obj<wbr>).tags)` | `{"config":"{\\"env\\":\\"prod\\",\\"tags\\":[\\"<wbr>tag-a\\",\\"tag-b\\",\\"tag-c\\"]}"}` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| withItems JSON objects are serialized | `withItems: [{name:'alice'...}, {name:'bob'...}]` | - | - | ✅ (Succeeded) | ✅ **loopWith items objects**: `addStep(..., c => c.register({ obj: expr.seriali<wbr>ze(c.item) }), { loopWith: makeItemsLoop([{name:<wbr>'alice',age:30},{name:'…` → (Succeeded) | ✅ |
| withItems iterates over array strings | `withItems: ['a','b','c']` | - | - | ✅ (Succeeded) | ✅ **loopWith items**: `addStep(..., c => c.register({ value: c.item }),<wbr> { loopWith: makeItemsLoop(['a','b','c']) })` → (Succeeded) | ✅ |
| withItems iterates over numbers | `withItems: [1,2,3]` | - | - | ✅ (Succeeded) | ✅ **loopWith items**: `addStep(..., c => c.register({ value: c.item }),<wbr> { loopWith: makeItemsLoop([1,2,3]) })` → (Succeeded) | ✅ |
| withParam deep nested objects/arrays require parsing top-level aggregate fields | `fromJSON(item['transport']) and fromJSON(item['r<wbr>outes']) for deep access` | `{"items":"[{\\"name\\":\\"proxy-a\\",\\"transpor<wbr>t\\":{\\"brokers\\":{\\"seed\\":[\\"kafka-a:9092<wbr>\\",\\"kafka-a:9093\\"]},\\"security\\":{\\…` | - | ✅ (Succeeded) | ⚠️ No builder support | ⚠️ |
| withParam from JSON array | `withParam: {{workflow.parameters.items}}` | `{"items":"[\\"one\\",\\"two\\",\\"three\\"]"}` | - | ✅ (Succeeded) | ✅ **loopWith param**: `addStep(..., c => c.register({ value: c.item }),<wbr> { loopWith: makeParameterLoop(ctx.inputs.items)<wbr> })` → (Succeeded) | ✅ |
| withParam nested field passed to downstream template | `pass item['kafkaConfig'] through templates and p<wbr>arse from input` | `{"proxies":"[{\\"name\\":\\"proxy-a\\",\\"kafkaC<wbr>onfig\\":{\\"connection\\":\\"kafka-a:9092\\",\\<wbr>"topic\\":\\"topic-a\\"},\\"listenPort\\":9…` | - | ✅ (Succeeded) | ✅ **withParam nested passthrough**: `Loop args: kafkaConfig: expr.get(c.item, 'kafkaC<wbr>onfig')<br>Intermediate and downstream templates are typed <wbr>as KafkaConfig;…` → (Succeeded) | ✅ |
| withParam over nested objects (fullMigration pattern) | `withParam over complex objects, accessing nested<wbr> fields` | `{"proxies":"[{\\"name\\":\\"proxy-a\\",\\"kafkaC<wbr>onfig\\":{\\"connection\\":\\"kafka-a:9092\\",\\<wbr>"topic\\":\\"topic-a\\"},\\"listenPort\\":9…` | - | ✅ (Succeeded) | ✅ **withParam nested objects**: `// c.item is a parsed object at runtime (top-lev<wbr>el fields accessible)<br>// nested aggregate fields are serialized at loo<wbr>p…` → (Succeeded) | ✅ |
| withParam over objects delivers parsed objects | `withParam: {{=toJSON(workflow.parameters.items)}<wbr>}` | `{"items":"[{\\"name\\":\\"alice\\",\\"age\\":30}<wbr>,{\\"name\\":\\"bob\\",\\"age\\":25}]"}` | - | ✅ (Succeeded) | ✅ **withParam over objects**: `c.item is T at runtime in withParam; use expr.ge<wbr>t(c.item, 'field') for field access, expr.serial<wbr>ize(c.item) to pass as …` → (Succeeded) | ✅ |

## Legend
- ✅ Pass — test passed, result matches expected
- ❌ Fail — test failed or result doesn't match
- ⚠️ No builder support — Argo feature has no builder API equivalent
- ⚠️ Partial — some builder variants pass, others fail
- \- Not tested
