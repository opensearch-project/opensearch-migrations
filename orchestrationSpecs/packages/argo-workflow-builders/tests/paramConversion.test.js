"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../src"); // <-- update
var tuple = function () {
    var xs = [];
    for (var _i = 0; _i < arguments.length; _i++) {
        xs[_i] = arguments[_i];
    }
    return xs;
};
var cbNoOpt = {
    defaults: {},
    register: function () { }
};
var keysNoOpt = tuple('a', 'b');
var typecheck_noOptional_ok = function () {
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbNoOpt, keysNoOpt);
};
var cbSomeOpt_ok = {
    defaults: { b: '', c: false },
    defaultKeys: ['b', 'c'],
    register: function () { }
};
var keysSomeOpt_ok = tuple('a', 'b', 'c', 'd');
var typecheck_someOptional_ok = function () {
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbSomeOpt_ok, keysSomeOpt_ok);
};
var cbSomeOpt_missingC = {
    defaults: { b: '' },
    defaultKeys: ['b'],
    register: function () { }
};
var cbSomeOpt_noList = {
    defaults: { b: '', c: false },
    register: function () { }
};
var cbAllOpt_ok = {
    defaults: { a: 1, b: '' },
    defaultKeys: ['a', 'b'],
    register: function () { }
};
var keysAllOpt_ordered = tuple('b', 'a');
var typecheck_keys_exact_different_order_ok = function () {
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbAllOpt_ok, keysAllOpt_ordered);
};
// OK: duplicates allowed by the type (set equality ignores multiplicity)
var keysAllOpt_dupes = ['a', 'a', 'b'];
var typecheck_keys_with_duplicates_ok = function () {
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbAllOpt_ok, keysAllOpt_dupes);
};
// ERROR: missing key 'b'
var keysAllOpt_missing = tuple('a');
var typecheck_keys_missing_error = function () {
    // @ts-expect-error - missing 'b' from keys list
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbAllOpt_ok, keysAllOpt_missing);
};
// ERROR: extra key 'x'
var keysAllOpt_extra = ['a', 'b', 'x'];
var typecheck_keys_extra_error = function () {
    // @ts-expect-error - 'x' is not a key of R_AllOptional
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbAllOpt_ok, keysAllOpt_extra);
};
// ERROR: widened arrays (string[]) lose literal key info
var keysWidened = ['a', 'b'];
var typecheck_widened_array_rejected = function () {
    // @ts-expect-error - widened string[] does not preserve literal keys of T
    (0, src_1.selectInputsFieldsAsExpressionRecord)({}, cbAllOpt_ok, keysWidened);
};
// ---------- Trivial runtime test so Jest executes the file ----------
describe('selectInputsFieldsAsExpressionRecordNew - type tests', function () {
    it('compiles type assertions (no runtime execution of type checks)', function () {
        expect(true).toBe(true);
    });
});
