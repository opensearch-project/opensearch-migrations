package org.opensearch.migrations.bulkload.models;

import java.util.List;

import com.beust.jcommander.Parameter;

public class DataFilterArgs {
    @Parameter(names = { "--index-allowlist" }, description = ("Optional.  List of index names to migrate"
        + " (e.g. 'logs_2024_01, logs_2024_02').  Default: all non-system indices (e.g. those not starting with '.', created by plugins)"), required = false)
    public List<String> indexAllowlist = List.of();

    @Parameter(names = {
        "--index-template-allowlist" }, description = ("Optional.  List of index template names to migrate"
            + " (e.g. 'posts_index_template1, posts_index_template2').  Default: all non-system indices (e.g. those not starting with '.', created by plugins)"), required = false)
    public List<String> indexTemplateAllowlist = List.of();

    @Parameter(names = {
        "--component-template-allowlist" }, description = ("Optional. List of component template names to migrate"
            + " (e.g. 'posts_template1, posts_template2').  Default: all non-system indices (e.g. those not starting with '.', created by plugins)"), required = false)
    public List<String> componentTemplateAllowlist = List.of();
}
