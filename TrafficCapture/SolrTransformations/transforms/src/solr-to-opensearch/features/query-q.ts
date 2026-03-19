/**
 * Query q parameter — convert Solr q param to OpenSearch query DSL.
 *
 * Uses the lucene parser library for full query syntax support:
 *   - *:* → match_all
 *   - field:value → term query
 *   - field:"phrase" → match_phrase query
 *   - field:[min TO max] → range query
 *   - Boolean operators: AND, OR, NOT
 *   - Prefix operators: +required -excluded
 *   - Boost: field:value^2
 *   - Implicit field → query_string (lets OpenSearch handle default field)
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 */
import * as lucene from 'lucene';
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

type QueryMap = Map<string, unknown>;

/** Term node: field:value or field:"phrase" */
interface TermNode {
  field: string;
  term: string;
  quoted: boolean;
  boost: number | null;
  prefix: '+' | '-' | null;
}

/** Range node: field:[min TO max] or field:{min TO max} */
interface RangeNode {
  field: string;
  term_min: string;
  term_max: string;
  inclusive: 'both' | 'none' | 'left' | 'right';
}

/** Binary expression: left OP right */
interface BinaryNode {
  left: LuceneNode;
  operator: 'AND' | 'OR' | 'NOT' | 'AND NOT' | '<implicit>';
  right: LuceneNode;
}

/** Wrapper node: { left: node } with no operator */
interface WrapperNode {
  left: TermNode | RangeNode;
}

type LuceneNode = TermNode | RangeNode | BinaryNode | WrapperNode;

function isRange(node: LuceneNode): node is RangeNode {
  return 'term_min' in node;
}

function isBool(node: LuceneNode): node is BinaryNode {
  return 'operator' in node;
}

function isWrapper(node: LuceneNode): node is WrapperNode {
  return 'left' in node && !('operator' in node);
}

/**
 * Converts a Lucene range node to OpenSearch range query.
 * 
 * Examples:
 *   - price:[10 TO 100] → { range: { price: { gte: '10', lte: '100' } } }
 *   - price:{10 TO 100} → { range: { price: { gt: '10', lt: '100' } } }
 *   - price:[* TO 100] → { range: { price: { lte: '100' } } }
 */
function convertRangeNode(node: RangeNode): QueryMap {
  const range = new Map<string, string>();
  const gteOp = node.inclusive === 'both' || node.inclusive === 'left';
  const lteOp = node.inclusive === 'both' || node.inclusive === 'right';
  if (node.term_min !== '*') range.set(gteOp ? 'gte' : 'gt', node.term_min);
  if (node.term_max !== '*') range.set(lteOp ? 'lte' : 'lt', node.term_max);
  return new Map([['range', new Map([[node.field, range]])]]);
}

/**
 * Converts a Lucene term node to OpenSearch query clause.
 * Handles prefix operators (+/-), implicit fields, phrases, and boosts.
 * 
 * Examples:
 *   - title:search → { term: { title: 'search' } }
 *   - title:"hello world" → { match_phrase: { title: 'hello world' } }
 *   - java (implicit field) → { query_string: { query: 'java' } }
 *   - title:search^2 → { term: { title: { value: 'search', boost: 2 } } }
 *   - +title:foo → prefix='+', clause={ term: { title: 'foo' } }
 */
function convertTerm(node: TermNode): { clause: QueryMap; prefix: string } {
  let field = node.field;
  let prefix = '';

  // Check prefix property first (used when field is implicit)
  if (node.prefix) {
    prefix = node.prefix;
  }
  // Lucene parser puts +/- prefix in field name when field is explicit
  else if (field.startsWith('+') || field.startsWith('-')) {
    prefix = field[0];
    field = field.slice(1);
  }

  // Match all
  if (field === '*' && node.term === '*') {
    return { clause: new Map([['match_all', new Map()]]), prefix };
  }

  // Implicit field - use query_string to let OpenSearch handle default field
  if (field === '<implicit>') {
    const clause = node.boost
      ? new Map([['query_string', new Map<string, unknown>([['query', node.term], ['boost', node.boost]])]])
      : new Map([['query_string', new Map([['query', node.term]])]]);
    return { clause, prefix };
  }

  // Phrase query
  if (node.quoted) {
    const clause = node.boost
      ? new Map([['match_phrase', new Map([[field, new Map<string, unknown>([['query', node.term], ['boost', node.boost]])]])]])
      : new Map([['match_phrase', new Map([[field, node.term]])]]);
    return { clause, prefix };
  }

  // Term query
  const clause = node.boost
    ? new Map([['term', new Map([[field, new Map<string, unknown>([['value', node.term], ['boost', node.boost]])]])]])
    : new Map([['term', new Map([[field, node.term]])]]);
  return { clause, prefix };
}

/**
 * Converts a Lucene binary node (AND, OR, NOT, implicit) to OpenSearch bool query.
 * 
 * Examples:
 *   - foo AND bar → { bool: { must: [left, right] } }
 *   - foo OR bar → { bool: { should: [left, right] } }
 *   - foo NOT bar → { bool: { must: [left], must_not: [right] } }
 *   - +foo -bar (implicit) → merges bool clauses: { bool: { must: [...], must_not: [...] } }
 *   - foo bar (implicit, no prefix) → { bool: { should: [left, right] } }
 */
function convertBoolNode(node: BinaryNode, convert: (n: LuceneNode) => QueryMap): QueryMap {
  const left = convert(node.left);
  const right = convert(node.right);

  switch (node.operator) {
    case 'AND':
      return new Map([['bool', new Map([['must', [left, right]]])]]);
    case 'OR':
      return new Map([['bool', new Map([['should', [left, right]]])]]);
    case 'NOT':
    case 'AND NOT':
      return new Map([['bool', new Map([['must', [left]], ['must_not', [right]]])]]);
    case '<implicit>': {
      // Merge bool clauses from prefix operators (+/-)
      const lBool = left.get('bool') as Map<string, QueryMap[]> | undefined;
      const rBool = right.get('bool') as Map<string, QueryMap[]> | undefined;
      if (lBool || rBool) {
        return new Map([['bool', new Map([
          ['must', [...(lBool?.get('must') || []), ...(rBool?.get('must') || [])]],
          ['must_not', [...(lBool?.get('must_not') || []), ...(rBool?.get('must_not') || [])]],
        ])]]);
      }
      return new Map([['bool', new Map([['should', [left, right]]])]]);
    }
  }
}

/**
 * Wraps a term clause with bool must/must_not based on prefix operator.
 * 
 * Examples:
 *   - +foo → { bool: { must: [clause] } }
 *   - -foo → { bool: { must_not: [clause] } }
 *   - foo (no prefix) → clause as-is
 */
function convertTermNode(node: TermNode): QueryMap {
  const { clause, prefix } = convertTerm(node);
  if (prefix === '+') return new Map([['bool', new Map([['must', [clause]]])]]);
  if (prefix === '-') return new Map([['bool', new Map([['must_not', [clause]]])]]);
  return clause;
}

/**
 * Main entry point for converting a Lucene AST node to OpenSearch query.
 * Dispatches to the appropriate converter based on node type.
 */
function convert(node: LuceneNode): QueryMap {
  // Unwrap { left: ... } wrapper
  if (isWrapper(node)) {
    return convert(node.left);
  }

  // Range query
  if (isRange(node)) {
    return convertRangeNode(node);
  }

  // Binary operator
  if (isBool(node)) {
    return convertBoolNode(node, convert);
  }

  // Term node with possible prefix
  return convertTermNode(node as TermNode);
}

export const request: MicroTransform<RequestContext> = {
  name: 'query-q',
  apply: (ctx) => {
    const q = ctx.params.get('q') || '*:*';
    if (!q || q === '*:*') {
      ctx.body.set('query', new Map([['match_all', new Map()]]));
    } else {
      try {
        ctx.body.set('query', convert(lucene.parse(q) as LuceneNode));
      } catch {
        ctx.body.set('query', new Map([['query_string', new Map([['query', q]])]]));
      }
    }

    // rows → size, start → from
    const rows = ctx.params.get('rows');
    if (rows) ctx.body.set('size', Number.parseInt(rows, 10));
    const start = ctx.params.get('start');
    if (start) ctx.body.set('from', Number.parseInt(start, 10));
  },
};
