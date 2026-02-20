import * as fs from "node:fs";
import path from "node:path";

// Configuration management helper scripts for production workflows
const configManagementHelpersDir = path.join(__dirname,'..','resources', 'configManagementHelpers');

const helperScript = fs.readFileSync(path.join(configManagementHelpersDir, 'etcdClientHelper.sh'), 'utf8');

function loadEtcdScript(scriptName: string) {
    const sourceRegex = /source *.\/etcdClientHelper.sh/;
    return fs.readFileSync(path.join(configManagementHelpersDir, scriptName), 'utf8')
        .replace(sourceRegex, helperScript);
}

export const decrementTlhScript = loadEtcdScript('decrement.sh');
export const cleanupTlhScript = loadEtcdScript('cleanup.sh');
