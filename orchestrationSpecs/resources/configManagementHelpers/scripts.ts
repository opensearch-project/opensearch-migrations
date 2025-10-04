import fs from 'fs';
import path from 'path';

// Get the directory where this script file is located
const scriptDir = __dirname;

export const initTlhScript = fs.readFileSync(path.join(scriptDir, 'init.sh'), 'utf8');
export const decrementTlhScript = fs.readFileSync(path.join(scriptDir, 'decrement.sh'), 'utf8');
export const cleanupTlhScript = fs.readFileSync(path.join(scriptDir, 'cleanup.sh'), 'utf8');
