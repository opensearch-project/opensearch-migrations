import {ScopeFn, ExtendScope, DataScope, GenericScope} from "@/schemas/workflowTypes";
import {TypescriptError} from "@/utils";

declare const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__: false;

// Notice that the inverse of this should be something like Extract<keyof S, string>
export type UniqueNameConstraintOutsideDeclaration<Name extends string, S, TypeWhenValid> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends false
        ? Name extends keyof S ? TypescriptError<`Name '${Name}' exists within ${keyof S & string}`> : TypeWhenValid
        : TypeWhenValid;

export type UniqueNameConstraintAtDeclaration<Name extends string, S> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends true
        ? Name extends keyof S ? TypescriptError<`Name '${Name}' exists within  ${keyof S & string}.`> : Name
        : Name;

export type ScopeIsEmptyConstraint<S, T> =
    keyof S extends never
        ? T
        : TypescriptError<`Scope must be empty but contains: ${keyof S & string}`>

export function extendScope<OS extends GenericScope, NS extends GenericScope>(orig: OS, fn: ScopeFn<OS, NS>): ExtendScope<OS, NS> {
    return {
        ...orig,
        ...fn(orig)
    } as ExtendScope<OS, NS>;
}
