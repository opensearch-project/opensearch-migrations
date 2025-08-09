import {FullMigration, TargetLatchHelpers} from "@/workflowTemplates/fullMigration";
import { FieldCount } from "@/utils";
import {renderWorkflowTemplate} from "@/renderers/argoResourceRenderer";

// console.log("TargetLatchHelper: " + JSON.stringify(TargetLatchHelpers, null, 2));
// console.log("FullMigration: " + JSON.stringify(FullMigration, null, 2));
// console.log("\n\n\n");

const finalConfigTlh = renderWorkflowTemplate(TargetLatchHelpers);
const finalConfigFm = renderWorkflowTemplate(FullMigration);
console.log("OUTPUT: ");
console.log(JSON.stringify(finalConfigTlh, null, 2));
console.log(JSON.stringify(finalConfigFm, null, 2));



//
//
// // Test the field count
// type Test1 = FieldCount<{ a: 1, b: 2, c: 3 }>           // Should be 3
// type Test2 = FieldCount<{ name: string, age: number }>   // Should be 2
// type Test3 = FieldCount<{}>                              // Should be 0
// type Test4 = FieldCount<{ single: boolean }>            // Should be 1
//
// // Utility that enforces exact field count
// type ExactlyNFields<T, N extends number> = FieldCount<T> extends N ? T : never
//
// // Function that only accepts objects with exactly 3 fields
// function processTripleField<T>(obj: ExactlyNFields<T, 3>): T {
//     return obj
// }
//
// // Test cases - these should work now:
// processTripleField({ a: 1, b: 2, c: 3 })                                    // ✅
// processTripleField({ name: "John", age: 30, email: "john@example.com" })    // ✅
//
// // These should still cause compile errors:
// // processTripleField({ a: 1, b: 2 })                    // ❌
// // processTripleField({ a: 1, b: 2, c: 3, d: 4 })        // ❌
//
// // More flexible function that accepts any specific field count
// function processExactFields<T, N extends number>(
//     obj: ExactlyNFields<T, N>,
//     expectedCount: N
// ): T {
//     return obj
// }
//
// // type Add<A extends number, B extends number> =
// //   [...BuildTuple<A>, ...BuildTuple<B>]['length']
//
// // type NoFieldOverlap<ORIG, ADDITIONS> =
// //   FieldCount<ORIG & ADDITIONS> extends Add<FieldCount<ORIG>, FieldCount<ADDITIONS>>
// //     ? ORIG & ADDITIONS
// //     : never
//
// // function processDualExactFields<ORIG, ADDITIONS, U extends ORIG & ADDITIONS, FieldCount<U> extends number>(
// //     orig: ORIG,
// //     additional: ADDITIONS
// // ): T {
// //     return obj
// // }
//
// // Usage examples:
// processExactFields({ a: 1, b: 2 }, 2)                    // ✅ 2 fields
// processExactFields({ x: 1, y: 2, z: 3, w: 4 }, 4)       // ✅ 4 fields
// // processExactFields({ a: 1, b: 2 }, 3)                 // ❌ Wrong count
//
// // Utility for creating field count constraints
// type AtLeastNFields<T, N extends number> = FieldCount<T> extends infer Count
//     ? Count extends number
//         ? N extends Count
//             ? never
//             : Count extends 0
//                 ? never
//                 : T
//         : never
//     : never
//
// type AtMostNFields<T, N extends number> = FieldCount<T> extends infer Count
//     ? Count extends number
//         ? Count extends 0
//             ? T
//             : [Count] extends [N]
//                 ? T
//                 : Count extends number
//                     ? N extends Count
//                         ? T
//                         : never
//                     : never
//         : never
//     : never
//
// // Range constraints
// function processFieldRange<T>(
//     obj: T & AtMostNFields<T, 5>
// ): T {
//     return obj
// }
//
// // Test range function:
// // processFieldRange({ a: 1, b: 2, c: 5, k: 1})                        // ✅ 2 fields (in range)
// processFieldRange({ a: 1, b: 2, c: 3, d: 4, e: 5 })     // ✅ 5 fields (in range)
// // processFieldRange({ a: 1 })                           // ❌ 1 field (too few)
// // processFieldRange({ a: 1, b: 2, c: 3, d: 4, e: 5, f: 6 }) // ❌ 6 fields (too many)
//
//
//
//
//
//
// // More robust overlap detection
// type NoKeyOverlap<A, B> = keyof A & keyof B extends never ? true : false
//
// type SafeCombine<A, B> = NoKeyOverlap<A, B> extends true
//   ? A & B
//   : never
//
// // The issue might be TypeScript's inference. Let's make it more explicit:
// function combineNoOverlap<A, B>(a: A, b: B): SafeCombine<A, B> {
//   return { ...a, ...b } as SafeCombine<A, B>
// }
//
// // Alternative approach that's more explicit about the constraint
// type StrictNoOverlap<A, B> = keyof A & keyof B extends never
//   ? A & B
//   : `Error: Keys ${keyof A & keyof B & string} overlap between objects`
//
// function combineStrict<A, B>(a: A, b: B): StrictNoOverlap<A, B> {
//   return { ...a, ...b } as StrictNoOverlap<A, B>
// }
//
// // Test the strict version:
// const strict1 = combineStrict({ a: 1 }, { b: 2 })      // ✅ Should work
// const strict2 = combineStrict({ a: 1 }, { a: 2 })      // ❌ Should show error message
//
// // Even more explicit - use a constraint on the function parameters
// function combineExplicit<A, B>(
//   a: A,
//   b: B & (keyof A & keyof B extends never ? B : never)
// ): A & B {
//   return { ...a, ...b }
// }
//
// // Test explicit version:
// const explicit1 = combineExplicit({ a: 1 }, { b: 2 })  // ✅ Should work
// // const explicit2 = combineExplicit({ a: 1 }, { a: 2 })  // ❌ Should fail on parameter 'b'
//
// // Most robust: Use conditional types with better error handling
// type EnsureNoOverlap<A, B> = {
//   [K in keyof A]: K extends keyof B
//     ? `Error: Key '${K & string}' exists in both objects`
//     : A[K]
// } & B
//
// function combineRobust<A, B>(a: A, b: B): EnsureNoOverlap<A, B> {
//   return { ...a, ...b } as EnsureNoOverlap<A, B>
// }
//
// // Test robust version:
// const robust1 = combineRobust({ a: 1 }, { b: 2 })     // ✅ Works
// const robust2 = combineRobust({ a: 1 }, { a: 2 })     // ❌ Should show specific error
//
// // Debug: Let's check what the types actually resolve to
// type DebugNoOverlap = NoKeyOverlap<{ a: number }, { a: number }>  // Should be false
// type DebugSafeCombine = SafeCombine<{ a: number }, { a: number }> // Should be never
//
// // Alternative that uses assertion functions for runtime + compile time
// function assertNoOverlap<A, B>(a: A, b: B): asserts b is B & (keyof A & keyof B extends never ? B : never) {
//   const aKeys = Object.keys(a as object)
//   const bKeys = Object.keys(b as object)
//   const overlap = aKeys.filter(key => bKeys.includes(key))
//
//   if (overlap.length > 0) {
//     throw new Error(`Key overlap detected: ${overlap.join(', ')}`)
//   }
// }
//
// function combineWithAssertion<A, B>(a: A, b: B): A & B {
//   assertNoOverlap(a, b)
//   return { ...a, ...b }
// }
//
// // Test assertion version:
// const assertion1 = combineWithAssertion({ a: 1 }, { b: 2 })  // ✅ Runtime + compile check
// // const assertion2 = combineWithAssertion({ a: 1 }, { a: 2 }) // ❌ Should fail
//
// // The simplest working solution - move the constraint to the parameter
// function combineSimple<A extends Record<string, any>, B extends Record<string, any>>(
//   a: A,
//   b: keyof A & keyof B extends never ? B : never
// ): A & B {
//   return { ...a, ...b }
// }
//
// // This should definitely catch the error:
// const simple1 = combineSimple({ a: 1 }, { b: 2 })     // ✅
// // const simple2 = combineSimple({ a: 1 }, { a: 2 })  // ❌ Parameter 'b' error
//
// console.log('Testing overlap detection...')
// // console.log('Debug types:', typeof {} as DebugNoOverlap, typeof {} as DebugSafeCombine)