import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import type { JsonObject } from '../nodes/PipePipe/McpClient';
import { collectItems, itemsOf, nextPageTokenOf } from '../nodes/PipePipe/Pagination';

/** A page source serving canned pages keyed by the token used to request them. */
function pagesFor(pages: Record<string, JsonObject>): {
	callTool(name: string, args?: JsonObject): Promise<JsonObject>;
	tokens: string[];
} {
	const tokens: string[] = [];
	return {
		tokens,
		async callTool(name, args) {
			assert.equal(name, 'get_more');
			const token = String((args ?? {}).pageToken);
			tokens.push(token);
			const page = pages[token];
			assert.ok(page !== undefined, `unexpected page token: ${token}`);
			return page;
		},
	};
}

describe('page helpers', () => {
	it('treats an omitted items key as an empty list', () => {
		// The extractor omits empty lists rather than serializing [].
		assert.deepEqual(itemsOf({ errors: ['boom'] }), []);
		assert.deepEqual(itemsOf({ items: [{ a: 1 }, 'not-an-object'] }), [{ a: 1 }]);
	});

	it('reads a next page token only when it is a non-empty string', () => {
		assert.equal(nextPageTokenOf({ nextPageToken: 'tok' }), 'tok');
		assert.equal(nextPageTokenOf({ nextPageToken: '' }), undefined);
		assert.equal(nextPageTokenOf({}), undefined);
	});
});

describe('collectItems', () => {
	it('follows next page tokens until the list is exhausted', async () => {
		const source = pagesFor({
			t1: { items: [{ n: 3 }, { n: 4 }], nextPageToken: 't2' },
			t2: { items: [{ n: 5 }] },
		});

		const collected = await collectItems(
			source,
			{ items: [{ n: 1 }, { n: 2 }], nextPageToken: 't1' },
			undefined,
		);

		assert.deepEqual(collected, [{ n: 1 }, { n: 2 }, { n: 3 }, { n: 4 }, { n: 5 }]);
		assert.deepEqual(source.tokens, ['t1', 't2']);
	});

	it('stops fetching once the limit is reached and trims the overflow', async () => {
		const source = pagesFor({
			t1: { items: [{ n: 3 }, { n: 4 }], nextPageToken: 't2' },
			t2: { items: [{ n: 5 }], nextPageToken: 't3' },
		});

		const collected = await collectItems(source, { items: [{ n: 1 }, { n: 2 }], nextPageToken: 't1' }, 3);

		assert.deepEqual(collected, [{ n: 1 }, { n: 2 }, { n: 3 }]);
		// One page was enough to satisfy the limit; t2 must never be requested.
		assert.deepEqual(source.tokens, ['t1']);
	});

	it('does not page when the first result already carries no token', async () => {
		const source = pagesFor({});
		const collected = await collectItems(source, { items: [{ n: 1 }] }, undefined);

		assert.deepEqual(collected, [{ n: 1 }]);
		assert.deepEqual(source.tokens, []);
	});

	it('breaks out when a server keeps handing back the same token', async () => {
		// Without a no-progress guard this would page forever.
		const source = pagesFor({ loop: { items: [{ n: 2 }], nextPageToken: 'loop' } });

		const collected = await collectItems(source, { items: [{ n: 1 }], nextPageToken: 'loop' }, undefined);

		assert.deepEqual(collected, [{ n: 1 }, { n: 2 }]);
		assert.deepEqual(source.tokens, ['loop']);
	});

	it('keeps paging past an empty page that still advances the cursor', async () => {
		const source = pagesFor({
			t1: { nextPageToken: 't2' },
			t2: { items: [{ n: 2 }] },
		});

		const collected = await collectItems(source, { items: [{ n: 1 }], nextPageToken: 't1' }, undefined);

		assert.deepEqual(collected, [{ n: 1 }, { n: 2 }]);
		assert.deepEqual(source.tokens, ['t1', 't2']);
	});
});
