import { App, Tags } from 'aws-cdk-lib';
import { readFileSync } from 'node:fs';
import { StackComposer } from "../lib/stack-composer";

export function createApp(): App {
  const app = new App();
  const versionFile = readFileSync('../../../VERSION', 'utf-8');
  const version = versionFile.replaceAll('\n', '');
  Tags.of(app).add("migration_deployment", version);

  const account = process.env.CDK_DEFAULT_ACCOUNT;
  const region = process.env.CDK_DEFAULT_REGION;
  const migrationsAppRegistryARN = process.env.MIGRATIONS_APP_REGISTRY_ARN;

  if (migrationsAppRegistryARN) {
    console.info(`App Registry mode is enabled for CFN stack tracking. Will attempt to import the App Registry application from the MIGRATIONS_APP_REGISTRY_ARN env variable of ${migrationsAppRegistryARN} and looking in the configured region of ${region}`);
  }

  // Temporarily allow both means for providing an additional migrations User Agent, but remove CUSTOM_REPLAYER_USER_AGENT
  // in future change
  let migrationsUserAgent = undefined
  if (process.env.CUSTOM_REPLAYER_USER_AGENT)
    migrationsUserAgent = process.env.CUSTOM_REPLAYER_USER_AGENT
  if (process.env.MIGRATIONS_USER_AGENT)
    migrationsUserAgent = process.env.MIGRATIONS_USER_AGENT

  new StackComposer(app, {
    migrationsAppRegistryARN: migrationsAppRegistryARN,
    migrationsUserAgent: migrationsUserAgent,
    migrationsSolutionVersion: version,
    env: { account: account, region: region }
  });

  return app;
}
