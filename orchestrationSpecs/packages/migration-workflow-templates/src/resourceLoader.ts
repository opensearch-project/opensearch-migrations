import * as fs from "node:fs";
import path from "node:path";

const scriptDir = path.join(__dirname,'..','resources', 'configManagementHelpers');

export const initTlhScript = fs.readFileSync(path.join(scriptDir, 'init.sh'), 'utf8');
export const decrementTlhScript = fs.readFileSync(path.join(scriptDir, 'decrement.sh'), 'utf8');
export const cleanupTlhScript = fs.readFileSync(path.join(scriptDir, 'cleanup.sh'), 'utf8');
