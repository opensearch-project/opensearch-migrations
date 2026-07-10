package org.opensearch.migrations.bulkload.common;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Built-in exclusion rules applied when no user allowlist is supplied.
 *
 * <p>Rules are tagged with the set of {@link FilterContext}s in which they apply.
 * Names like {@code logs} or {@code metrics} are Elastic/Fleet-shipped
 * <em>template</em> names, so excluding them from template creation is correct,
 * but excluding them from index migration would silently drop any user index
 * that happens to share the name. Each rule therefore declares which contexts
 * it applies to.</p>
 *
 * <p>When an allowlist is supplied, built-in exclusions are bypassed entirely and
 * only the allowlist is consulted.</p>
 */
public class FilterScheme {
    private FilterScheme() {}

    /**
     * What kind of thing is being filtered. Built-in exclusion rules are tagged
     * with the contexts they apply to so the same name (e.g. {@code logs}) can
     * be banned as a template but allowed as a user index.
     */
    public enum FilterContext {
        INDEX,
        LEGACY_INDEX_TEMPLATE,
        INDEX_TEMPLATE,
        COMPONENT_TEMPLATE;

        public static final Set<FilterContext> TEMPLATE_CONTEXTS = Collections.unmodifiableSet(EnumSet.of(
            LEGACY_INDEX_TEMPLATE,
            INDEX_TEMPLATE,
            COMPONENT_TEMPLATE
        ));
    }

    private enum MatchType { PREFIX, SUFFIX, EXACT }

    private static final class ExclusionRule {
        final String pattern;
        final MatchType matchType;
        final Set<FilterContext> appliesTo;

        ExclusionRule(String pattern, MatchType matchType, Set<FilterContext> appliesTo) {
            this.pattern = pattern;
            this.matchType = matchType;
            this.appliesTo = appliesTo;
        }

        boolean matches(String item) {
            switch (matchType) {
                case PREFIX: return item.startsWith(pattern);
                case SUFFIX: return item.endsWith(pattern);
                case EXACT:  return item.equals(pattern);
                default: throw new IllegalStateException("Unreachable: " + matchType);
            }
        }
    }

    // Shorthand helpers to keep the rule table readable.
    private static final Set<FilterContext> ALL = EnumSet.allOf(FilterContext.class);
    private static final Set<FilterContext> TEMPLATES_ONLY = FilterContext.TEMPLATE_CONTEXTS;

    private static ExclusionRule prefix(String p, Set<FilterContext> ctx)  { return new ExclusionRule(p, MatchType.PREFIX, ctx); }
    private static ExclusionRule suffix(String p, Set<FilterContext> ctx)  { return new ExclusionRule(p, MatchType.SUFFIX, ctx); }
    private static ExclusionRule exact(String p, Set<FilterContext> ctx)   { return new ExclusionRule(p, MatchType.EXACT,  ctx); }

    /**
     * Built-in exclusion rules. Each rule declares the contexts in which it applies.
     *
     * <p>When adding or adjusting rules, err toward the narrower context set:
     * a rule that is clearly a template / component-template name (i.e. the
     * shape of a name users never assign to a real data index) should be
     * {@link #TEMPLATES_ONLY}, not {@link #ALL}.</p>
     */
    private static final List<ExclusionRule> EXCLUSION_RULES = List.of(
        // Prefixes: system/stack indices that should never be migrated as data OR as templates.
        prefix(".",                     ALL),
        prefix("kibana",                ALL),
        prefix("searchguard",           ALL),
        prefix("sg7-",                  ALL),

        // Prefixes: Elastic-stack data-stream / integration indices. Data-plane
        // items with these prefixes are stack-managed, not user data.
        prefix("apm-",                  ALL),
        prefix("behavioral_analytics-", ALL),
        prefix("elastic-connectors-",   ALL),
        prefix("elastic_agent.",        ALL),
        prefix("ilm-history-",          ALL),
        prefix("logs-elastic_agent",    ALL),
        prefix("logs-endpoint.",        ALL),
        prefix("logs-index_pattern",    ALL),
        prefix("logs-system.",          ALL),
        prefix("metricbeat-",           ALL),
        prefix("metrics-elastic_agent", ALL),
        prefix("metrics-endpoint.",     ALL),
        prefix("metrics-index_pattern", ALL),
        prefix("metrics-metadata-",     ALL),
        prefix("metrics-system.",       ALL),
        prefix("profiling-",            ALL),
        prefix("synthetics-",           ALL),

        // Prefixes: component-template / index-template name shapes. Indices
        // can't contain '@' so these are effectively template-only already,
        // but scoping them explicitly documents intent.
        prefix("@abc_template@",        TEMPLATES_ONLY),
        prefix("apm@",                  TEMPLATES_ONLY),
        prefix("data-streams-",         TEMPLATES_ONLY),
        prefix("data-streams@",         TEMPLATES_ONLY),
        prefix("ecs@",                  TEMPLATES_ONLY),

        // Suffixes: all template / component-template naming conventions.
        // Index names can't contain '@' in Elasticsearch/OpenSearch, so these
        // never match real indices — scope them to templates.
        suffix("@ilm",                  TEMPLATES_ONLY),
        suffix("@lifecycle",            TEMPLATES_ONLY),
        suffix("@mappings",             TEMPLATES_ONLY),
        suffix("@package",              TEMPLATES_ONLY),
        suffix("@settings",             TEMPLATES_ONLY),
        suffix("@template",             TEMPLATES_ONLY),
        suffix("@tsdb-settings",        TEMPLATES_ONLY),

        // Exact names: stack-shipped template names. A user index literally
        // called "logs" / "metrics" / "traces" is unusual but legal; the
        // template of the same name is stack-managed and should not migrate.
        exact("agentless",              TEMPLATES_ONLY),
        exact("elastic-connectors",     TEMPLATES_ONLY),
        exact("ilm-history",            TEMPLATES_ONLY),
        exact("logs",                   TEMPLATES_ONLY),
        exact("logs-mappings",          TEMPLATES_ONLY),
        exact("logs-settings",          TEMPLATES_ONLY),
        exact("logs-tsdb-settings",     TEMPLATES_ONLY),
        exact("metrics",                TEMPLATES_ONLY),
        exact("metrics-mappings",       TEMPLATES_ONLY),
        exact("metrics-settings",       TEMPLATES_ONLY),
        exact("metrics-tsdb-settings",  TEMPLATES_ONLY),
        exact("profiling",              TEMPLATES_ONLY),
        exact("search-acl-filter",      TEMPLATES_ONLY),
        exact("synthetics",             TEMPLATES_ONLY),
        exact("tenant_template",        TEMPLATES_ONLY),
        exact("traces",                 TEMPLATES_ONLY),
        exact("traces-mappings",        TEMPLATES_ONLY),
        exact("traces-settings",        TEMPLATES_ONLY),
        exact("traces-tsdb-settings",   TEMPLATES_ONLY),

        // Exact names: X-Pack watcher indices — these are real indices on the
        // source cluster and should not be migrated as data.
        exact("triggered_watches",      ALL),
        exact("watches",                ALL),
        exact("watch_history_3",        ALL)
    );

    /**
     * Build a predicate that accepts items the migration should process.
     *
     * @param allowlist user-supplied allowlist; when non-empty, built-in
     *                  exclusions are bypassed and only the allowlist is
     *                  consulted
     * @param context   what kind of thing is being filtered; used to select
     *                  which built-in exclusion rules apply
     */
    public static Predicate<String> filterByAllowList(List<String> allowlist, FilterContext context) {
        // Validate and pre-compile all allowlist entries to fail fast on invalid patterns
        final List<AllowlistEntry> compiledEntries;
        if (allowlist != null && !allowlist.isEmpty()) {
            compiledEntries = allowlist.stream()
                .map(AllowlistEntry::new)
                .toList();
        } else {
            compiledEntries = null;
        }

        return item -> {
            if (compiledEntries == null) {
                return !isExcluded(item, context);
            } else {
                return compiledEntries.stream()
                    .anyMatch(entry -> entry.matches(item));
            }
        };
    }

    private static boolean isExcluded(String item, FilterContext context) {
        for (ExclusionRule rule : EXCLUSION_RULES) {
            if (rule.appliesTo.contains(context) && rule.matches(item)) {
                return true;
            }
        }
        return false;
    }
}
