import * as fs from "node:fs";
import path from "node:path";

// ============================================================================
// WARNING: TEST HELPER RESOURCE LOADER
// ============================================================================
// This file loads test-specific resources that create a logical dependency cycle:
// - migration-workflow-templates package provides workflow templates
// - workflow CLI (in migration console) depends on migration-workflow-templates
// - Test scripts depend on workflow CLI
//
// TODO: Move this file and test workflows to a separate package (e.g.,
// migration-workflow-templates-test) to break this dependency cycle.
// ============================================================================

const testMigrationHelpersDir = path.join(__dirname,'..','resources', 'testMigrationHelpers');

export const configureAndSubmitScript = fs.readFileSync(path.join(testMigrationHelpersDir, 'configureAndSubmit.sh'), 'utf8');
export const monitorScript = fs.readFileSync(path.join(testMigrationHelpersDir, 'monitor.sh'), 'utf8');
