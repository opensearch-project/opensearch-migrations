import * as fs from "node:fs";
import path from "node:path";

// Configuration management helper scripts for production workflows
const configManagementHelpersDir = path.join(__dirname,'..','resources', 'configManagementHelpers');

export const initTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'init.sh'), 'utf8');
export const decrementTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'decrement.sh'), 'utf8');
export const cleanupTlhScript = fs.readFileSync(path.join(configManagementHelpersDir, 'cleanup.sh'), 'utf8');
