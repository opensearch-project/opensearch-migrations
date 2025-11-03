import {BaseExpression, selectInputsFieldsAsExpressionRecord, Serialized} from '../src'; // <-- update

const tuple = <T extends readonly string[]>(...xs: T) => xs;

type R_NoOptional = { a: number; b: string };
type R_SomeOptional = { a: number; b?: string; c?: boolean; d: number };
type R_AllOptional = { a?: number; b?: string };

const cbNoOpt = {
    defaults: {},
    register: () => {}
} as const;

const keysNoOpt = tuple('a', 'b');

const typecheck_noOptional_ok = () => {
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_NoOptional>, cbNoOpt, keysNoOpt);
};

const cbSomeOpt_ok = {
    defaults: { b: '', c: false },
    defaultKeys: ['b', 'c'] as const,
    register: () => {}
} as const;

const keysSomeOpt_ok = tuple('a', 'b', 'c', 'd');

const typecheck_someOptional_ok = () => {
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_SomeOptional>, cbSomeOpt_ok, keysSomeOpt_ok);
};

const cbSomeOpt_missingC = {
    defaults: { b: '' },
    defaultKeys: ['b'] as const,
    register: () => {}
} as const;

const cbSomeOpt_noList = {
    defaults: { b: '', c: false },
    register: () => {}
} as const;

const cbAllOpt_ok = {
    defaults: { a: 1, b: '' },
    defaultKeys: ['a', 'b'] as const,
    register: () => {}
} as const;

const keysAllOpt_ordered = tuple('b', 'a');
const typecheck_keys_exact_different_order_ok = () => {
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_AllOptional>, cbAllOpt_ok, keysAllOpt_ordered);
};

// OK: duplicates allowed by the type (set equality ignores multiplicity)
const keysAllOpt_dupes = ['a', 'a', 'b'] as const;
const typecheck_keys_with_duplicates_ok = () => {
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_AllOptional>, cbAllOpt_ok, keysAllOpt_dupes);
};

// ERROR: missing key 'b'
const keysAllOpt_missing = tuple('a');
const typecheck_keys_missing_error = () => {
    // @ts-expect-error - missing 'b' from keys list
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_AllOptional>, cbAllOpt_ok, keysAllOpt_missing);
};

// ERROR: extra key 'x'
const keysAllOpt_extra = ['a', 'b', 'x'] as const;
const typecheck_keys_extra_error = () => {
    // @ts-expect-error - 'x' is not a key of R_AllOptional
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_AllOptional>, cbAllOpt_ok, keysAllOpt_extra);
};

// ERROR: widened arrays (string[]) lose literal key info
const keysWidened: string[] = ['a', 'b'];
const typecheck_widened_array_rejected = () => {
    // @ts-expect-error - widened string[] does not preserve literal keys of T
    selectInputsFieldsAsExpressionRecord({} as BaseExpression<R_AllOptional>, cbAllOpt_ok, keysWidened);
};

// ---------- Trivial runtime test so Jest executes the file ----------
describe('selectInputsFieldsAsExpressionRecordNew - type tests', () => {
    it('compiles type assertions (no runtime execution of type checks)', () => {
        expect(true).toBe(true);
    });
});
