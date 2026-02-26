/**
 * Query q parameter — parse Solr query syntax into OpenSearch query DSL.
 *
 * Supports:
 *   - *:* → match_all
 *   - field:value → term query (keyword) or match query (text)
 *   - field:"phrase" → match_phrase query
 *   - field:[lo TO hi] → range query
 *   - field:val* → wildcard query
 *   - field:val~ / field:val~N → fuzzy query
 *   - "phrase"~N → match_phrase with slop
 *   - bare terms → query_string against default field
 *   - Boolean: AND, OR, NOT, +, -
 *   - Parenthesized groups
 *   - defType=edismax with qf, mm
 *
 * Request-only.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

// ---------------------------------------------------------------------------
// Token types for the Solr query lexer
// ---------------------------------------------------------------------------

type Token =
  | { type: 'TERM'; value: string }
  | { type: 'FIELD'; value: string }       // "field:" — colon consumed
  | { type: 'PHRASE'; value: string }       // quoted string (quotes stripped)
  | { type: 'AND' } | { type: 'OR' } | { type: 'NOT' }
  | { type: 'PLUS' } | { type: 'MINUS' }
  | { type: 'LPAREN' } | { type: 'RPAREN' }
  | { type: 'RANGE'; lo: string; hi: string; loInc: boolean; hiInc: boolean }
  | { type: 'EOF' };

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

function tokenize(input: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  const len = input.length;

  while (i < len) {
    // skip whitespace
    if (input[i] === ' ' || input[i] === '\t') { i++; continue; }

    // parentheses
    if (input[i] === '(') { tokens.push({ type: 'LPAREN' }); i++; continue; }
    if (input[i] === ')') { tokens.push({ type: 'RPAREN' }); i++; continue; }

    // +/- prefix operators
    if (input[i] === '+' && (i + 1 < len && input[i + 1] !== ' ')) {
      tokens.push({ type: 'PLUS' }); i++; continue;
    }
    if (input[i] === '-' && (i + 1 < len && input[i + 1] !== ' ')) {
      tokens.push({ type: 'MINUS' }); i++; continue;
    }

    // quoted phrase
    if (input[i] === '"') {
      i++;
      let phrase = '';
      while (i < len && input[i] !== '"') {
        if (input[i] === '\\' && i + 1 < len) { phrase += input[++i]; }
        else { phrase += input[i]; }
        i++;
      }
      if (i < len) i++; // skip closing quote
      // check for proximity ~N
      if (i < len && input[i] === '~') {
        i++;
        let num = '';
        while (i < len && input[i] >= '0' && input[i] <= '9') { num += input[i++]; }
        tokens.push({ type: 'PHRASE', value: phrase + '~' + (num || '2') });
      } else {
        tokens.push({ type: 'PHRASE', value: phrase });
      }
      continue;
    }

    // range: [lo TO hi] or {lo TO hi}
    if (input[i] === '[' || input[i] === '{') {
      const loInc = input[i] === '[';
      i++;
      // skip whitespace
      while (i < len && input[i] === ' ') i++;
      let lo = '';
      while (i < len && input[i] !== ' ') { lo += input[i++]; }
      // skip " TO "
      while (i < len && input[i] === ' ') i++;
      if (input.slice(i, i + 2) === 'TO') i += 2;
      while (i < len && input[i] === ' ') i++;
      let hi = '';
      while (i < len && input[i] !== ']' && input[i] !== '}') { hi += input[i++]; }
      hi = hi.trim();
      const hiInc = i < len && input[i] === ']';
      if (i < len) i++; // skip closing bracket
      tokens.push({ type: 'RANGE', lo, hi, loInc, hiInc });
      continue;
    }

    // word (may be followed by : making it a field)
    let word = '';
    while (i < len && !' \t()[]{}:"'.includes(input[i])) {
      if (input[i] === '\\' && i + 1 < len) { word += input[++i]; }
      else { word += input[i]; }
      i++;
    }

    if (!word) { i++; continue; }

    // check for boolean keywords
    if (word === 'AND' || word === '&&') { tokens.push({ type: 'AND' }); continue; }
    if (word === 'OR' || word === '||') { tokens.push({ type: 'OR' }); continue; }
    if (word === 'NOT' || word === '!') { tokens.push({ type: 'NOT' }); continue; }

    // check if next char is : (field prefix)
    if (i < len && input[i] === ':') {
      i++; // consume colon
      tokens.push({ type: 'FIELD', value: word });
      continue;
    }

    tokens.push({ type: 'TERM', value: word });
  }

  tokens.push({ type: 'EOF' });
  return tokens;
}

// ---------------------------------------------------------------------------
// Parser — recursive descent producing OpenSearch query DSL
// ---------------------------------------------------------------------------

type QueryDSL = Record<string, unknown>;

class Parser {
  private pos = 0;
  constructor(private tokens: Token[]) {}

  private peek(): Token { return this.tokens[this.pos]; }
  private advance(): Token { return this.tokens[this.pos++]; }

  parse(): QueryDSL {
    const result = this.parseOr();
    return result;
  }

  private parseOr(): QueryDSL {
    const clauses: QueryDSL[] = [this.parseAnd()];
    while (this.peek().type === 'OR') {
      this.advance();
      clauses.push(this.parseAnd());
    }
    if (clauses.length === 1) return clauses[0];
    return { bool: { should: clauses, minimum_should_match: 1 } };
  }

  private parseAnd(): QueryDSL {
    const clauses: QueryDSL[] = [this.parseUnary()];
    while (this.peek().type === 'AND') {
      this.advance();
      clauses.push(this.parseUnary());
    }
    // implicit AND: two adjacent terms without operator
    while (this.peek().type !== 'EOF' && this.peek().type !== 'OR' &&
           this.peek().type !== 'RPAREN' && this.peek().type !== 'AND') {
      clauses.push(this.parseUnary());
    }
    if (clauses.length === 1) return clauses[0];
    return { bool: { must: clauses } };
  }

  private parseUnary(): QueryDSL {
    const tok = this.peek();
    if (tok.type === 'NOT') {
      this.advance();
      return { bool: { must_not: [this.parsePrimary()] } };
    }
    if (tok.type === 'MINUS') {
      this.advance();
      return { bool: { must_not: [this.parsePrimary()] } };
    }
    if (tok.type === 'PLUS') {
      this.advance();
      return this.parsePrimary();
    }
    return this.parsePrimary();
  }

  private parsePrimary(): QueryDSL {
    const tok = this.peek();

    if (tok.type === 'LPAREN') {
      this.advance();
      const inner = this.parseOr();
      if (this.peek().type === 'RPAREN') this.advance();
      return inner;
    }

    if (tok.type === 'FIELD') {
      this.advance();
      const field = tok.value;
      return this.parseFieldValue(field);
    }

    if (tok.type === 'PHRASE') {
      this.advance();
      return this.buildPhrase(undefined, tok.value);
    }

    if (tok.type === 'TERM') {
      this.advance();
      return this.buildTerm(undefined, tok.value);
    }

    // fallback — consume and return match_all
    this.advance();
    return { match_all: {} };
  }

  private parseFieldValue(field: string): QueryDSL {
    const tok = this.peek();

    if (tok.type === 'RANGE') {
      this.advance();
      return this.buildRange(field, tok.lo, tok.hi, tok.loInc, tok.hiInc);
    }

    if (tok.type === 'PHRASE') {
      this.advance();
      return this.buildPhrase(field, tok.value);
    }

    if (tok.type === 'TERM') {
      this.advance();
      return this.buildTerm(field, tok.value);
    }

    if (tok.type === 'LPAREN') {
      // field:(a OR b)
      this.advance();
      const inner = this.parseFieldGroup(field);
      if (this.peek().type === 'RPAREN') this.advance();
      return inner;
    }

    // field with no value — treat as exists
    return { exists: { field } };
  }

  private parseFieldGroup(field: string): QueryDSL {
    const clauses: QueryDSL[] = [];
    while (this.peek().type !== 'RPAREN' && this.peek().type !== 'EOF') {
      if (this.peek().type === 'OR') { this.advance(); continue; }
      if (this.peek().type === 'AND') { this.advance(); continue; }
      const tok = this.peek();
      if (tok.type === 'TERM') {
        this.advance();
        clauses.push(this.buildTerm(field, tok.value));
      } else if (tok.type === 'PHRASE') {
        this.advance();
        clauses.push(this.buildPhrase(field, tok.value));
      } else {
        break;
      }
    }
    if (clauses.length === 1) return clauses[0];
    return { bool: { should: clauses, minimum_should_match: 1 } };
  }

  private buildTerm(field: string | undefined, value: string): QueryDSL {
    // *:* or just *
    if (value === '*' && (!field || field === '*')) return { match_all: {} };
    if (value === '*') return { exists: { field: field! } };

    // wildcard
    if (value.includes('*') || value.includes('?')) {
      return { wildcard: { [field || '_all']: { value } } };
    }

    // fuzzy: term~N
    const fuzzyMatch = value.match(/^(.+)~(\d*)$/);
    if (fuzzyMatch) {
      const term = fuzzyMatch[1];
      const fuzziness = fuzzyMatch[2] || 'AUTO';
      return { fuzzy: { [field || '_all']: { value: term, fuzziness } } };
    }

    // plain term
    if (field) {
      return { term: { [field]: value } };
    }
    return { query_string: { query: value } };
  }

  private buildPhrase(field: string | undefined, value: string): QueryDSL {
    // proximity: "phrase"~N
    const proxMatch = value.match(/^(.+)~(\d+)$/);
    if (proxMatch) {
      return { match_phrase: { [field || '_all']: { query: proxMatch[1], slop: parseInt(proxMatch[2]) } } };
    }
    return { match_phrase: { [field || '_all']: value } };
  }

  private buildRange(field: string, lo: string, hi: string, loInc: boolean, hiInc: boolean): QueryDSL {
    const range: Record<string, unknown> = {};
    if (lo !== '*') range[loInc ? 'gte' : 'gt'] = lo;
    if (hi !== '*') range[hiInc ? 'lte' : 'lt'] = hi;
    return { range: { [field]: range } };
  }
}

// ---------------------------------------------------------------------------
// edismax support
// ---------------------------------------------------------------------------

function buildEdismaxQuery(q: string, qf: string, mm?: string): QueryDSL {
  const fields = qf.split(/\s+/).filter(Boolean);
  if (q === '*:*' || q === '*') return { match_all: {} };
  const result: QueryDSL = {
    multi_match: {
      query: q,
      fields,
      type: 'best_fields',
    },
  };
  if (mm) (result.multi_match as any).minimum_should_match = mm;
  return result;
}

// ---------------------------------------------------------------------------
// Public: the micro-transform
// ---------------------------------------------------------------------------

function parseSolrQuery(q: string, params: URLSearchParams): QueryDSL {
  if (!q || q === '*:*') return { match_all: {} };

  const defType = params.get('defType');
  if (defType === 'edismax' || defType === 'dismax') {
    const qf = params.get('qf');
    if (qf) return buildEdismaxQuery(q, qf, params.get('mm') || undefined);
  }

  const tokens = tokenize(q);
  return new Parser(tokens).parse();
}

export const request: MicroTransform<RequestContext> = {
  name: 'query-q',
  apply: (ctx) => {
    const q = ctx.params.get('q') || '*:*';
    ctx.body.query = parseSolrQuery(q, ctx.params);
  },
};
