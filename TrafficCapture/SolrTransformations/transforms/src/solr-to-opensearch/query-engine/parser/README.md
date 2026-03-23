# Parser — Design Decisions

## Why a Custom PEG Grammar

We evaluated existing libraries ([liqe](https://github.com/gajus/liqe), [lucene-kit](https://github.com/oxdev03/lucene-kit)) as alternatives to a custom grammar and chose to implement a custom PEG-based grammar using [Peggy](https://github.com/peggyjs/peggy).

The migration use case requires support for Solr-specific query syntax — implicit boolean operators, field boosts, all four range bracket combinations (`[]`, `{}`, `[}`, `{]`), `*:*` match-all, and parser-specific behaviors introduced by eDisMax and DisMax. While liqe and lucene-kit provide general-purpose query parsing, neither is designed to fully support Solr's query language. liqe targets in-memory object filtering with its own dialect. lucene-kit extends Lucene syntax with features like regex, variables, and functions, but its coverage of Solr-specific constructs is incomplete. Both would require significant AST reshaping or post-processing to bridge the gap between their output and the structure needed for OpenSearch DSL generation.

Both libraries also impose constraints on grammar coverage and AST structure that make them difficult to extend for Solr-specific features such as local parameters, spatial queries, function queries, and streaming expressions. Adapting them would likely require maintaining custom forks or complex adapter layers, reducing the practical benefit of reuse.

By defining a custom PEG grammar, we retain full control over syntax coverage, AST design, and extensibility. The grammar produces AST nodes directly — no adapter layer needed. Parser-specific semantics (such as eDisMax field expansion via `qf` and `pf`) are handled as clean post-parse AST transformations rather than being embedded in the grammar. This provides a more maintainable and predictable foundation for the Solr-to-OpenSearch query translation pipeline, with a grammar that is ~120 lines and has no dependencies beyond Peggy.
