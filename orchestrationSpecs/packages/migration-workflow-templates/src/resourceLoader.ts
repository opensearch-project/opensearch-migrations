import * as fs from "node:fs";
import path from "node:path";

// Configuration management helper scripts for production workflows
const configManagementHelpersDir = path.join(__dirname,'..','resources', 'configManagementHelpers');

export const initTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'init.sh'), 'utf8');
export const decrementTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'decrement.sh'), 'utf8');
export const cleanupTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'cleanup.sh'), 'utf8');

// TODO: Separate test helper scripts into a dedicated package
// These test migration helpers should be moved to a separate package (e.g., migration-workflow-templates-test)
// to avoid mixing production workflow resources with test-specific resources and to break the dependency cycle
// between this package and the workflow CLI.
const testMigrationHelpersDir = path.join(__dirname,'..','resources', 'testMigrationHelpers');

export const configureAndSubmitScript = fs.readFileSync(path.join(testMigrationHelpersDir, 'configureAndSubmit.sh'), 'utf8');
export const monitorScript = fs.readFileSync(path.join(testMigrationHelpersDir, 'monitor.sh'), 'utf8');
