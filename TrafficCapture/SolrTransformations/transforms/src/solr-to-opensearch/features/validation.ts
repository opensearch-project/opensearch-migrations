/**
 * Validation — fail fast on null/empty inputs and unsupported Solr features.
 *
 * Runs as a global request transform (before endpoint-specific transforms).
 * All validation rules are generic and injected by registry.ts via initValidation().
 *
 * Features declare:
 *   - params / paramPrefixes — for supported param discovery
 *   - paramRules — for param type validation (integer, boolean, json)
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export type ParamType = 'integer' | 'boolean' | 'json' | 'rejectPattern';

/** Data validation rule — checks value format. @example `{ name: 'rows', type: 'integer' }` */
interface DataRule {
  /** Solr param name this rule validates. Example: `'rows'`, `'hl'`, `'json.facet'` */
  name: string;
  /** `'integer'` — must be a valid integer | `'boolean'` — `'true'`/`'false'` | `'json'` — valid JSON */
  type: 'integer' | 'boolean' | 'json';
}

/** Pattern rejection rule — rejects values matching a regex. @example `{ name: 'q', type: 'rejectPattern', pattern: String.raw`^\{!`, reason: 'Local params not supported' }` */
interface RejectRule {
  /** Solr param name this rule validates. Example: `'q'`, `'sort'`, `'fl'` */
  name: string;
  type: 'rejectPattern';
  /** Regex pattern — rejects if value matches. Example: `String.raw`^\{!`` */
  pattern: string;
  /** Human-readable error shown when pattern matches. Example: `'Local params ({!...}) syntax in q is not supported'` */
  reason: string;
}

/** Discriminated union — use `type` to distinguish between data checks and pattern rejections. */
export type ParamRule = DataRule | RejectRule;

let supportedParams: Set<string> = new Set();
let supportedPrefixes: string[] = [];
let paramRules: ParamRule[] = [];
/** Pre-compiled regexes keyed by pattern string — built once in initValidation(). */
const compiledPatterns = new Map<string, RegExp>();

/** Called by registry.ts after aggregating from all features. */
export function initValidation(
  params: Set<string>,
  prefixes: string[],
  rules: ParamRule[],
): void {
  supportedParams = params;
  supportedPrefixes = prefixes;
  paramRules = rules;
  compiledPatterns.clear();
  for (const rule of rules) {
    if (rule.type === 'rejectPattern' && rule.pattern) {
      compiledPatterns.set(rule.pattern, new RegExp(rule.pattern));
    }
  }
}

function isSupported(key: string): boolean {
  if (supportedParams.has(key)) return true;
  return supportedPrefixes.some((p) => key.startsWith(p));
}

const TYPE_VALIDATORS: Record<ParamType, (val: string, name: string, rule: ParamRule) => string | null> = {
  integer: (val, name) => {
    const trimmed = val.trim();
    return trimmed === '' || !/^-?\d+$/.test(trimmed)
      ? `'${name}' must be a valid integer, got '${val}'`
      : null;
  },
  boolean: (val, name) =>
    val !== 'true' && val !== 'false'
      ? `'${name}' must be 'true' or 'false', got '${val}'`
      : null,
  json: (val, name) => {
    if (!val.trim()) return `'${name}' must be valid JSON, got empty value`;
    try {
      JSON.parse(val);
      return null;
    } catch {
      return `'${name}' must be valid JSON, got '${val.length > 50 ? val.slice(0, 50) + '...' : val}'`;
    }
  },
  rejectPattern: (val, name, rule) => {
    const re = rule.pattern ? compiledPatterns.get(rule.pattern) : undefined;
    if (re?.test(val)) {
      return rule.reason || `'${name}' contains unsupported syntax`;
    }
    return null;
  },
};

export const request: MicroTransform<RequestContext> = {
  name: 'validation',
  match: (ctx) => ctx.endpoint === 'select',
  apply: (ctx) => {
    // Unsupported param detection (fail fast on unknown params)
    const unsupported: string[] = [];
    for (const key of ctx.params.keys()) {
      if (!isSupported(key) && !key.startsWith('_')) {
        unsupported.push(key);
      }
    }
    if (unsupported.length > 0) {
      throw new Error(`Unsupported Solr parameters: ${unsupported.join(', ')}`);
    }

    // Param type checks (auto-discovered from features)
    for (const rule of paramRules) {
      const val = ctx.params.get(rule.name);
      if (val != null) {
        const error = TYPE_VALIDATORS[rule.type](val, rule.name, rule);
        if (error) throw new Error(`Validation failed: ${error}`);
      }
    }
  },
};
