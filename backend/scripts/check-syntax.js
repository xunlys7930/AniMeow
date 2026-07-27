const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const ignoredDirectories = new Set(['node_modules', 'public']);

function collectJavaScriptFiles(directory) {
    const files = [];
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        if (entry.isDirectory() && ignoredDirectories.has(entry.name)) continue;
        const absolutePath = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            files.push(...collectJavaScriptFiles(absolutePath));
        } else if (entry.isFile() && entry.name.endsWith('.js')) {
            files.push(absolutePath);
        }
    }
    return files;
}

let failed = false;
for (const file of collectJavaScriptFiles(root)) {
    const result = spawnSync(process.execPath, ['--check', file], {
        encoding: 'utf8',
    });
    if (result.status !== 0) {
        failed = true;
        process.stderr.write(result.stderr || result.stdout || `Syntax check failed: ${file}\n`);
    }
}

if (failed) process.exit(1);
console.log('Backend JavaScript syntax check passed.');
