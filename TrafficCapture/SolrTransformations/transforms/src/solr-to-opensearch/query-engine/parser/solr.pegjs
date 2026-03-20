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

// OR expression: one or more AND expressions separated by "OR" or by
// implicit adjacency (two terms next to each other without an operator).
// `a OR b` produces BoolNode { or: [a, b], implicit: false }.
// `a b` produces BoolNode { or: [a, b], implicit: true }.
// The `implicit` flag lets parser.ts distinguish between explicit OR and
// implicit adjacency when applying q.op=AND.
orExpr
  = head:andExpr tail:(_ ("OR" __)? andExpr)+ {
      const hasExplicitOr = tail.some(t => t[1] !== null);
      const children = [head, ...tail.map(t => t[2])];
      if (children.length === 1) return children[0];
      return { type: 'bool', and: [], or: children, not: [], implicit: !hasExplicitOr };
    }
  / andExpr

// AND expression: one or more unary expressions separated by "AND".
// AND binds tighter than OR: `a OR b AND c` → `a OR (b AND c)`.
andExpr
  = head:unaryExpr tail:(__ "AND" __ unaryExpr)+ {
      const children = [head, ...tail.map(t => t[3])];
      if (children.length === 1) return children[0];
      return { type: 'bool', and: children, or: [], not: [] };
    }
  / unaryExpr

// NOT expression: unary prefix operator. Binds tightest of the boolean ops.
// `NOT title:java` → BoolNode { not: [FieldNode] }
// Recursive: `NOT NOT a` is valid (double negation).
unaryExpr
  = "NOT" __ expr:unaryExpr {
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
matchAll
  = "*:*" { return { type: 'matchAll' }; }

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
// These produce nodes with field=''. The parser.ts wrapper resolves the
// empty field to the default field (df) from the params map after parsing.

// PhraseNode (bare): "hello world" without a field prefix.
// `"hello world"` → PhraseNode { text: "hello world", field: "" }
// field is resolved to df by parser.ts after parsing.
barePhrase
  = "\"" text:$[^"]* "\"" boost:boost? {
      const node = { type: 'phrase', text: text, field: '' };
      if (boost !== null) return { type: 'boost', child: node, value: boost };
      return node;
    }

// FieldNode (bare): a search term without a field prefix.
// `java` → FieldNode { field: "", value: "java" }
// field is resolved to df by parser.ts after parsing.
// Keywords (AND, OR, NOT, TO) are excluded to prevent them from being
// consumed as values — they return undefined so peggy backtracks.
bareValue
  = val:valueChars boost:boost? {
      if (['AND','OR','NOT','TO'].includes(val)) return undefined;
      const node = { type: 'field', field: '', value: val };
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
valueChars
  = $[a-zA-Z0-9._\-*#$@?+/]+

// Field name identifier: starts with a letter or underscore, followed by
// value characters. More restrictive first character prevents numbers from
// being parsed as field names (e.g., `123` is a value, not a field).
identifier
  = $([a-zA-Z_][a-zA-Z0-9._\-*#$@?+/]*)

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
