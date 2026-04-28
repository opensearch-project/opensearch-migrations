/**
 * Peggy grammar for Solr query syntax.
 *
 * Handles the core query syntax shared by all Solr query parser types
 * (Lucene, eDisMax, DisMax). Parser-specific behavior (e.g., eDisMax's
 * multi-field distribution via qf/pf) is handled as a post-parse AST
 * transformation, not in this grammar — the query syntax is the same
 * across parser types; what differs is the semantics applied after
 * parsing (e.g., how bare terms are expanded using runtime parameters
 * like qf, pf, mm). A PEG grammar is purely syntactic and has no
 * access to the request params map, so keeping this separation means
 * one grammar for all parser types with no duplication.
 *
 * Operator precedence (lowest → highest):
 *   1. OR  — `a OR b` or implicit adjacency `a b`
 *   2. AND — `a AND b`
 *   3. NOT — `NOT a`
 *   4. ()  — grouping overrides precedence
 *
 * Example: `a OR b AND NOT c` parses as `a OR (b AND (NOT c))`
 */

// ─── Entry point ──────────────────────────────────────────────────────────────

// A query is an OR expression surrounded by optional whitespace.
// An empty query (e.g., "") produces a MatchAllNode.
// When a {!...} local params prefix is present, the body is captured as raw
// text. The parser (parser.ts) re-parses the body based on the `type` param.
query
  = _ lp:localParams body:$(.+)? _ {
      return { type: 'localParams', params: lp, rawBody: body || null, body: null };
    }
  / _ expr:orExpr _ { return expr; }
  / _ { return { type: 'matchAll' }; }

// ─── Local params ─────────────────────────────────────────────────────────────
// Solr local params prefix: {!key=value ...}query
// Carries metadata like parser type, default field, query fields.

localParams
  = "{!" _ shortForm:localParamsShortForm? pairs:(_ localParamsPair)* _ "}" {
      const result = [];
      if (shortForm) result.push({ key: 'type', value: shortForm, deref: false });
      for (const p of pairs) result.push(p[1]);
      return result;
    }

localParamsShortForm
  = id:localParamsKey !("=") { return id; }

localParamsPair
  = key:localParamsKey "=" val:localParamsValue {
      return { key: key, value: val.value, deref: val.deref };
    }

localParamsKey
  = $([a-zA-Z_][a-zA-Z0-9._]*)

localParamsValue
  = "'" chars:localParamsSingleQuotedChar* "'" { return { value: chars.join(''), deref: false }; }
  / "\"" chars:localParamsDoubleQuotedChar* "\"" { return { value: chars.join(''), deref: false }; }
  / "$" name:localParamsKey { return { value: name, deref: true }; }
  / val:$[^ \t\n\r}]+ { return { value: val, deref: false }; }

localParamsSingleQuotedChar
  = "\\" c:. { return c; }
  / [^'\\]

localParamsDoubleQuotedChar
  = "\\" c:. { return c; }
  / [^"\\]

// ─── Boolean operators ────────────────────────────────────────────────────────
// Precedence is encoded by nesting: orExpr → andExpr → unaryExpr → primary.
// Deeper rules bind tighter.

// OR expression: one or more AND expressions separated by "OR" / "||" or by
// implicit adjacency (two terms next to each other without an operator).
// `a OR b` produces BoolNode { or: [a, b], implicit: false }.
// `a b` produces BoolNode { or: [a, b], implicit: true }.
// The `implicit` flag lets parser.ts distinguish between explicit OR and
// implicit adjacency when applying q.op=AND.
orExpr
  = head:andExpr tail:(_ (orOp __)? andExpr)+ {
      const hasExplicitOr = tail.some(t => t[1] !== null);
      const children = [head, ...tail.map(t => t[2])];
      if (children.length === 1) return children[0];
      return { type: 'bool', and: [], or: children, not: [], implicit: !hasExplicitOr };
    }
  / andExpr

// OR operator: "OR" or "||"
orOp = "OR" / "||"

// AND expression: one or more unary expressions separated by "AND" / "&&".
// AND binds tighter than OR: `a OR b AND c` → `a OR (b AND c)`.
andExpr
  = head:unaryExpr tail:(__ andOp __ unaryExpr)+ {
      const children = [head, ...tail.map(t => t[3])];
      if (children.length === 1) return children[0];
      return { type: 'bool', and: children, or: [], not: [] };
    }
  / unaryExpr

// AND operator: "AND" or "&&"
andOp = "AND" / "&&"

// NOT expression: unary prefix operator. Binds tightest of the boolean ops.
// `NOT title:java` → BoolNode { not: [FieldNode] }
// `!title:java` → BoolNode { not: [FieldNode] }
// Recursive: `NOT NOT a` is valid (double negation).
unaryExpr
  = notOp _ expr:unaryExpr {
      return { type: 'bool', and: [], or: [], not: [expr] };
    }
  / prefixSequence

// NOT operator: "NOT" (requires trailing whitespace) or "!" (no whitespace needed)
notOp = "NOT" __ / "!"

// Prefix sequence: consecutive +/- prefixed terms form an AND relationship.
// In Solr, `+A +B` means "A required AND B required", not "(+A) OR (+B)".
// This rule directly builds a BoolNode with and/not arrays from the prefix operators.
//
// Examples:
//   `+A +B` → BoolNode { and: [A, B], or: [], not: [] }
//   `-A -B` → BoolNode { and: [], or: [], not: [A, B] }
//   `+A -B` → BoolNode { and: [A], or: [], not: [B] }
prefixSequence
  = head:prefixWithType tail:(__ prefixWithType)+ {
      const all = [head, ...tail.map(t => t[1])];
      const and = all.filter(p => p.op === '+').map(p => p.expr);
      const not = all.filter(p => p.op === '-').map(p => p.expr);
      return { type: 'bool', and, or: [], not, implicit: false };
    }
  / prefixExpr

// Returns { op: '+' or '-', expr: node } for use by prefixSequence
prefixWithType
  = "+" _ expr:primary { return { op: '+', expr }; }
  / "-" _ expr:primary { return { op: '-', expr }; }

// Prefix expressions: +term (required) and -term (prohibited)
// `+title:java` → BoolNode { and: [FieldNode] } (must match)
// `-title:java` → BoolNode { not: [FieldNode] } (must not match)
// These are unary prefix operators that bind to the immediately following term.
prefixExpr
  = "+" _ expr:primary {
      return { type: 'bool', and: [expr], or: [], not: [] };
    }
  / "-" _ expr:primary {
      return { type: 'bool', and: [], or: [], not: [expr] };
    }
  / primary

// ─── Primary expressions ─────────────────────────────────────────────────────
// The smallest building blocks. Order matters in PEG — first match wins.
// matchAll (*:*) must come before fieldExpr to prevent `*` being consumed
// as a field name.
// filterFunc must come before bareValue to prevent `filter` being consumed
// as a bare term.

primary
  = group
  / matchAll
  / filterFunc
  / fieldExpr
  / barePhrase
  / bareValue

// FilterNode: filter(subquery) — Solr's inline filter caching syntax.
// `filter(inStock:true)` → FilterNode { child: FieldNode }
// The inner clause is cached in Solr's filter cache and executed as a
// constant-score (non-scoring) clause. In OpenSearch, this maps to
// bool.filter for equivalent non-scoring behavior.
// See: https://solr.apache.org/guide/solr/latest/query-guide/standard-query-parser.html
filterFunc
  = "filter(" _ expr:query _ ")" boost:boost? {
      const node = { type: 'filter', child: expr };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// GroupNode: parenthesized sub-expression that overrides operator precedence.
// `(a OR b) AND c` → GroupNode wrapping BoolNode.
// Optional boost: `(a OR b)^2` → BoostNode wrapping GroupNode.
group
  = "(" _ expr:query _ ")" boost:boost? {
      const node = { type: 'group', child: expr };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// MatchAllNode: `*:*` matches every document.
// Must be checked before fieldExpr to prevent `*` being parsed as a field name.
// Supports optional boost: `*:*^2` → BoostNode wrapping MatchAllNode.
matchAll
  = "*:*" boost:boost? {
      const node = { type: 'matchAll' };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// ─── Field expressions ───────────────────────────────────────────────────────
// Matches field:value, field:"phrase", and field:[range] / field:{range}.
// Each variant is a separate PEG alternative (first match wins).
// All field expressions support an optional boost suffix: field:value^2.

fieldExpr
  // PhraseNode: field:"quoted text"
  // `title:"hello world"` → PhraseNode { text: "hello world", field: "title" }
  = field:identifier ":" "\"" text:$[^"]* "\"" boost:boost? {
      const node = { type: 'phrase', text: text, field: field };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }
  // RangeNode (inclusive both): field:[low TO high]
  // `price:[10 TO 100]` → RangeNode { lowerInclusive: true, upperInclusive: true }
  / field:identifier ":" "[" _ lo:rangeVal _ "TO" _ hi:rangeVal _ "]" boost:boost? {
      const node = { type: 'range', field, lower: lo, upper: hi, lowerInclusive: true, upperInclusive: true };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }
  // RangeNode (exclusive both): field:{low TO high}
  // `price:{10 TO 100}` → RangeNode { lowerInclusive: false, upperInclusive: false }
  / field:identifier ":" "{" _ lo:rangeVal _ "TO" _ hi:rangeVal _ "}" boost:boost? {
      const node = { type: 'range', field, lower: lo, upper: hi, lowerInclusive: false, upperInclusive: false };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }
  // RangeNode (mixed: inclusive lower, exclusive upper): field:[low TO high}
  / field:identifier ":" "[" _ lo:rangeVal _ "TO" _ hi:rangeVal _ "}" boost:boost? {
      const node = { type: 'range', field, lower: lo, upper: hi, lowerInclusive: true, upperInclusive: false };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }
  // RangeNode (mixed: exclusive lower, inclusive upper): field:{low TO high]
  / field:identifier ":" "{" _ lo:rangeVal _ "TO" _ hi:rangeVal _ "]" boost:boost? {
      const node = { type: 'range', field, lower: lo, upper: hi, lowerInclusive: false, upperInclusive: true };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }
  // FieldNode: field:value (unquoted)
  // `title:java` → FieldNode { field: "title", value: "java" }
  / field:identifier ":" val:valueChars boost:boost? {
      const node = { type: 'field', field: field, value: val };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// ─── Bare expressions (no field prefix) ──────────────────────────────────────
// These produce BareNode. The parser.ts wrapper sets the defaultField
// property based on the df parameter.

// BareNode (bare phrase): "hello world" without a field prefix.
// `"hello world"` → BareNode { query: "hello world", isPhrase: true }
// defaultField is set by parser.ts based on df parameter.
barePhrase
  = "\"" text:$[^"]* "\"" boost:boost? {
      const node = { type: 'bare', value: text, isPhrase: true };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// BareNode (bare term): a search term without a field prefix.
// `java` → BareNode { query: "java", isPhrase: false }
// defaultField is set by parser.ts based on df parameter.
// Keywords (AND, OR, NOT, TO) are excluded to prevent them from being
// consumed as values — they return undefined so peggy backtracks.
bareValue
  = val:valueChars boost:boost? {
      if (['AND','OR','NOT','TO'].includes(val)) return undefined;
      const node = { type: 'bare', value: val, isPhrase: false };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// ─── Terminals ───────────────────────────────────────────────────────────────

// Range bound value: alphanumeric string or * (for unbounded ranges like [* TO 100]).
rangeVal
  = "*" { return '*'; }
  / $[a-zA-Z0-9._\-]+

// Unquoted value characters: letters, digits, and common special chars.
// Determines what can appear in unquoted field values.
// Includes ~ to parse fuzzy syntax (roam~, roam~1) so we can throw a clear error.
// Note: + and - are special characters (prefix operators) and must be escaped
// if used literally in values. They are NOT included here.
valueChars
  = $[a-zA-Z0-9._*#$@?/~]+

// Field name identifier: starts with a letter or underscore, followed by
// alphanumeric and common special chars. More restrictive first character
// prevents numbers from being parsed as field names (e.g., `123` is a value).
// Note: + and - are special characters (prefix operators) and must be escaped
// if used literally in field names. They are NOT included here.
identifier
  = $([a-zA-Z_][a-zA-Z0-9._*#$@?/]*)

// BoostNode modifier: ^N where N is a number (integer or decimal).
// `title:java^2` → BoostNode { child: FieldNode, value: 2 }
// `title:java^0.5` → BoostNode { child: FieldNode, value: 0.5 }
// Returns the numeric value, not a string.
// Note: Solr also accepts NaN and Infinity via Java's Float.parseFloat().
// These are not currently supported as they produce undefined scoring behavior.
boost
  = "^" val:$[0-9.]+ { return parseFloat(val); }

// Required whitespace — at least one space, tab, or newline.
// Used between keywords: `a AND b` requires spaces around AND.
__ "required whitespace"
  = [ \t\n\r]+

// Optional whitespace — zero or more spaces, tabs, or newlines.
// Used inside ranges, groups, and at query boundaries.
_ "whitespace"
  = [ \t\n\r]*

// ─── Function query entry point ──────────────────────────────────────────────

funcQuery
  = _ fc:funcCall _ { return fc; }

funcCall
  = name:funcName "(" _ args:funcArgList? _ ")" {
      return { type: 'func', name: name, args: args || [] };
    }

funcArgList
  = head:funcArg tail:(_ "," _ funcArg)* {
      return [head, ...tail.map(t => t[3])];
    }

funcArg
  = funcNumericLiteral
  / funcStringConstant
  / funcCall
  / funcWildcard
  / funcFieldRef

funcWildcard
  = "*" { return { kind: 'field', name: '*' }; }

funcNumericLiteral
  = val:$("-"? [0-9]+ ("." [0-9]+)? ([eE] [+\-]? [0-9]+)?) {
      return { kind: 'numeric', value: parseFloat(val) };
    }

funcStringConstant
  = "'" chars:funcSingleQuotedChar* "'" {
      return { kind: 'string', value: chars.join('') };
    }

funcSingleQuotedChar
  = "\\" c:. { return c; }
  / [^'\\]

funcFieldRef
  = name:funcIdentifier !("(") {
      return { kind: 'field', name: name };
    }

funcName
  = $([a-zA-Z_][a-zA-Z0-9_]*)

funcIdentifier
  = $([a-zA-Z_][a-zA-Z0-9._]*)
