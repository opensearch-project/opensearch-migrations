import * as fs from "node:fs";
import path from "node:path";

const configManagementHelpersDir = path.join(__dirname,'..','resources', 'configManagementHelpers');

export const initTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'init.sh'), 'utf8');
export const decrementTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'decrement.sh'), 'utf8');
export const cleanupTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'cleanup.sh'), 'utf8');

const testMigrationHelpersDir = path.join(__dirname,'..','resources', 'testMigrationHelpers');

export const configureAndSubmitScript = fs.readFileSync(path.join(testMigrationHelpersDir, 'configureAndSubmit.sh'), 'utf8');
export const monitorScript = fs.readFileSync(path.join(testMigrationHelpersDir, 'monitor.sh'), 'utf8');
