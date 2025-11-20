import {ExtendScope, GenericScope, ScopeFn} from "./workflowTypes";
import {TypescriptError} from "../utils";
import {InputParamDef} from "./parameterSchemas";

// true: worse LSP, but squigglies under the name declaration
// false: squigglies under other parts of named constructs instead of the declaration, but better LSP support
declare const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__: false;

// Notice that the inverse of this should be something like Extract<keyof S, string>
export type UniqueNameConstraintOutsideDeclaration<Name extends string, S, TypeWhenValid> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends false
        ? Lowercase<Name> extends Lowercase<keyof S & string> ? TypescriptError<`Name '${Name}' exists within ${keyof S & string}`> : TypeWhenValid
        : TypeWhenValid;

export type UniqueNameConstraintAtDeclaration<Name extends string, S> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends true
        ? Lowercase<Name> extends Lowercase<keyof S & string> ? TypescriptError<`Name '${Name}' exists within  ${keyof S & string}.`> : Name
        : Name;

// shared-types.ts - Export these types from a shared file
export type FieldSpecs = Record<string, string | number | boolean>;

export type FieldSpecsToInputParams<T extends FieldSpecs> = {
    [K in keyof T]: InputParamDef<any, true>;
};

// Like UniqueNameConstraintAtDeclaration, but for a set of field names
export type FieldGroupConstraint<
    Existing extends Record<string, any>,
    NewGroup extends Record<string, any>,
> =
    Extract<keyof Existing & keyof NewGroup, PropertyKey> extends never
        ? NewGroup
        : TypescriptError<`Duplicate input parameter(s): ${Extract<keyof Existing & keyof NewGroup, string>}`>;

export type ScopeIsEmptyConstraint<S, T> =
    keyof S extends never
        ? T
        : TypescriptError<`Scope must be empty but contains: ${keyof S & string}`>
 
/**
 * Helper type to extract ContainerScope from a ContainerBuilder type.
 * This allows us to check for resources in the container scope.
 */
export type ExtractContainerScope<T> = T extends { bodyScope: infer CS } ? CS : never;

export function extendScope<OS extends GenericScope, NS extends GenericScope>(orig: OS, fn: ScopeFn<OS, NS>): ExtendScope<OS, NS> {
    return {
        ...orig,
        ...fn(orig)
    } as ExtendScope<OS, NS>;
}
