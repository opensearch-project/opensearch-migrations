/**
 * Phrase boost (pf) — builds the OpenSearch DSL for Solr's pf parameter.
 *
 * pf (phrase fields) boosts documents where all terms in the query appear
 * close together as a phrase in the specified fields. It is scoring-only —
 * it never affects which documents match, only how they are ranked.
 *
 * This is a query-level concern (operates on the full query string, not
 * per-AST-node), so it lives here as a transformer utility rather than
 * in the AST or orchestrator.
 *
 * Output: a multi_match with type "phrase" intended for bool.should.
 *
 * Example: pf=title^50 body^20 ps=10
 *   → Map{"multi_match" → Map{"query"→q, "fields"→["title^50","body^20"], "type"→"phrase", "slop"→10}}
 *
 * @see https://solr.apache.org/guide/solr/latest/query-guide/dismax-query-parser.html#pf-phrase-fields-parameter
 */

import { parseFieldSpecs } from '../parser/qf';

/**
 * Build the pf phrase boost DSL, or return null if pf is not set.
 *
 * @param query - The raw query string (q param value) used as the phrase text.
 * @param params - All request params. Reads `pf` (fields) and `ps` (phrase slop).
 */
export function buildPhraseBoost(
  query: string,
  params: ReadonlyMap<string, string>,
): Map<string, any> | null {
  const pfFields = parseFieldSpecs(params.get('pf'));
  if (!pfFields) return null;

  const slop = Number.parseInt(params.get('ps') ?? '0', 10);
  const entries: [string, any][] = [
    ['query', query],
    ['fields', pfFields],
    ['type', 'phrase'],
  ];
  if (slop > 0) entries.push(['slop', slop]);

  return new Map([['multi_match', new Map<string, any>(entries)]]);
}

/**
 * Wrap a main DSL query with a pf phrase boost in a bool query.
 *
 * If pf is set, returns bool { must: mainDsl, should: phraseBoostDsl }.
 * If pf is not set, returns mainDsl unchanged.
 *
 * @param mainDsl - The translated main query DSL.
 * @param query   - The raw query string used as the phrase text for pf.
 * @param params  - All request params. Reads `pf` and `ps`.
 */
export function applyPhraseBoost(
  mainDsl: Map<string, any>,
  query: string,
  params: ReadonlyMap<string, string>,
): Map<string, any> {
  const pfDsl = buildPhraseBoost(query, params);
  if (!pfDsl) return mainDsl;

  // bool — combines two independent scoring clauses into one query.
  //
  // must — the main qf query: determines which documents match and contributes
  //   to the base score. Documents that don't satisfy this clause are excluded.
  //
  // should — the pf phrase boost: scoring-only, never filters out documents.
  //   In OpenSearch, a should clause inside a bool that already has a must
  //   clause is purely additive — it boosts the score of documents where the
  //   phrase matches, but documents that don't match the phrase still appear
  //   in results with their base qf score unchanged.
  return new Map<string, any>([
    ['bool', new Map<string, any>([
      ['must', mainDsl],
      ['should', pfDsl],
    ])],
  ]);
}
