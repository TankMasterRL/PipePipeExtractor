// tsc only emits JavaScript, so the node's icon and codex file have to be copied
// into dist/ alongside it for n8n to pick them up.
import { cp, mkdir, readdir } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const assetExtensions = ['.svg', '.png', '.node.json'];

async function* walk(directory) {
	for (const entry of await readdir(directory, { withFileTypes: true })) {
		const path = join(directory, entry.name);
		if (entry.isDirectory()) {
			yield* walk(path);
		} else {
			yield path;
		}
	}
}

let copied = 0;
for await (const path of walk(join(root, 'nodes'))) {
	if (!assetExtensions.some((extension) => path.endsWith(extension))) {
		continue;
	}
	const destination = join(root, 'dist', relative(root, path));
	await mkdir(dirname(destination), { recursive: true });
	await cp(path, destination);
	copied += 1;
}

console.log(`copied ${copied} node asset(s) into dist/`);
