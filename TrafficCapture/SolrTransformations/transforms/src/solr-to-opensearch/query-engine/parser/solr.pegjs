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
query
  = _ expr:orExpr _ { return expr; }
  / _ { return { type: 'matchAll' }; }

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
  / prefixExpr

// NOT operator: "NOT" (requires trailing whitespace) or "!" (no whitespace needed)
notOp = "NOT" __ / "!"

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

primary
  = group
  / matchAll
  / fieldExpr
  / barePhrase
  / bareValue

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
  = $[a-zA-Z0-9._*#$@?/]+

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
