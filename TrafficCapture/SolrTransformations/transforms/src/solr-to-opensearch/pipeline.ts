/**
 * Micro-transform pipeline — endpoint-based routing with O(1) dispatch.
 *
 * Instead of running every transform's match() against every message,
 * transforms are grouped by endpoint. A /select request only runs
 * select transforms.
 */
import type { SolrEndpoint } from './context';

/** A small, focused transformation step. */
export interface MicroTransform<Ctx> {
  name: string;
  /** Optional guard — if omitted, always applies for its endpoint group. */
  match?: (ctx: Ctx) => boolean;
  apply: (ctx: Ctx) => void;
}

/** Transforms grouped by endpoint for O(1) routing. */
export interface TransformRegistry<Ctx> {
  global: MicroTransform<Ctx>[];
  byEndpoint: Partial<Record<SolrEndpoint, MicroTransform<Ctx>[]>>;
}

/** Run all matching transforms for the given context's endpoint. */
export function runPipeline<Ctx extends { endpoint: SolrEndpoint }>(
  registry: TransformRegistry<Ctx>,
  ctx: Ctx,
): void {
  for (const t of registry.global) {
    if (!t.match || t.match(ctx)) t.apply(ctx);
  }
  const group = registry.byEndpoint[ctx.endpoint];
  if (group) {
    for (const t of group) {
      if (!t.match || t.match(ctx)) t.apply(ctx);
    }
  }
}
