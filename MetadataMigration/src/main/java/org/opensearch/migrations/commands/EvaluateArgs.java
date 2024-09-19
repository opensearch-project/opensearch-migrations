package org.opensearch.migrations.commands;

import org.opensearch.migrations.MigrateOrEvaluateArgs;

import com.beust.jcommander.Parameters;

@Parameters(commandNames = "evaluate", commandDescription = "Inspects items from a source to determine which can be placed on a target cluster")
public class EvaluateArgs extends MigrateOrEvaluateArgs {
}
