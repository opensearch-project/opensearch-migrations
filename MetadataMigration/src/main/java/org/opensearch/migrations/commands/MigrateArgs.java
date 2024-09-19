package org.opensearch.migrations.commands;

import org.opensearch.migrations.MigrateOrEvaluateArgs;

import com.beust.jcommander.Parameters;

@Parameters(commandNames = "migrate", commandDescription = "Migrates items from a source and recreates them on the target cluster")
public class MigrateArgs extends MigrateOrEvaluateArgs {
}
