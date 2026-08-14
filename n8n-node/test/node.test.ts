import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';

import type { JsonObject } from '../nodes/PipePipe/McpClient';
import { PipePipe } from '../nodes/PipePipe/PipePipe.node';
import { fakeExecuteFunctions, type FakeExecuteOptions } from './support/fakeExecute';
import { StubMcpServer, toolError, toolResult } from './support/stubServer';

let server: StubMcpServer;

beforeEach(async () => {
	server = new StubMcpServer({ sessionId: 'node-session' });
	await server.start();
});

afterEach(async () => server.stop());

/** Runs the node against the stub server and returns the emitted item payloads. */
async function run(options: Omit<FakeExecuteOptions, 'endpoint'>): Promise<JsonObject[]> {
	const context = fakeExecuteFunctions({ ...options, endpoint: server.url });
	const [output] = await new PipePipe().execute.call(context);
	return output.map((item) => item.json as JsonObject);
}

/** The arguments the stub received for a given tool call. */
function argumentsOf(tool: string): JsonObject {
	const request = server.requests.find(
		(entry) => ((entry.body?.params ?? {}) as JsonObject).name === tool,
	);
	assert.ok(request !== undefined, `the node never called "${tool}"`);
	return ((request.body!.params as JsonObject).arguments ?? {}) as JsonObject;
}

describe('PipePipe node', () => {
	it('lists services as one item per service', async () => {
		server.tool('list_services', () =>
			toolResult({
				services: [
					{ id: 0, name: 'YouTube' },
					{ id: 3, name: 'BiliBili' },
				],
			}),
		);

		const output = await run({ parameters: { resource: 'service', operation: 'getAll' } });

		assert.deepEqual(output, [
			{ id: 0, name: 'YouTube' },
			{ id: 3, name: 'BiliBili' },
		]);
	});

	it('searches, passing filters through and paging up to the limit', async () => {
		server
			.tool('search', () =>
				toolResult({ items: [{ name: 'one' }, { name: 'two' }], nextPageToken: 'p2' }),
			)
			.tool('get_more', () => toolResult({ items: [{ name: 'three' }, { name: 'four' }] }));

		const output = await run({
			parameters: {
				resource: 'search',
				operation: 'search',
				serviceId: 0,
				query: 'lofi',
				contentFilters: [1, 2],
				sortFilters: [],
				returnAll: false,
				limit: 3,
			},
		});

		assert.deepEqual(output, [{ name: 'one' }, { name: 'two' }, { name: 'three' }]);
		assert.deepEqual(argumentsOf('search'), {
			serviceId: 0,
			query: 'lofi',
			contentFilters: [1, 2],
			sortFilters: [],
		});
	});

	it('returns every page when Return All is set', async () => {
		let page = 0;
		server
			.tool('search', () => toolResult({ items: [{ n: 1 }], nextPageToken: 'p2' }))
			.tool('get_more', () => {
				page += 1;
				return page === 1
					? toolResult({ items: [{ n: 2 }], nextPageToken: 'p3' })
					: toolResult({ items: [{ n: 3 }] });
			});

		const output = await run({
			parameters: {
				resource: 'search',
				operation: 'search',
				serviceId: 0,
				query: 'lofi',
				contentFilters: [],
				sortFilters: [],
				returnAll: true,
			},
		});

		assert.deepEqual(output, [{ n: 1 }, { n: 2 }, { n: 3 }]);
	});

	it('sends the Language and Country options to the extractor', async () => {
		server.tool('search', () => toolResult({ items: [{ name: 'one' }] }));

		await run({
			parameters: {
				resource: 'search',
				operation: 'search',
				serviceId: 0,
				query: 'lofi',
				contentFilters: [],
				sortFilters: [],
				returnAll: true,
				options: { language: ' en-GB ', country: 'SE' },
			},
		});

		const args = argumentsOf('search');
		// Trimmed, so a stray space in the UI field never becomes part of the code.
		assert.equal(args.language, 'en-GB');
		assert.equal(args.country, 'SE');
	});

	it('sends the Language option on URL-addressed operations too', async () => {
		server.tool('get_stream', () => toolResult({ name: 'a stream' }));

		await run({
			parameters: {
				resource: 'stream',
				operation: 'get',
				url: 'https://example.com/watch?v=1',
				options: { language: 'en' },
			},
		});

		assert.deepEqual(argumentsOf('get_stream'), {
			language: 'en',
			url: 'https://example.com/watch?v=1',
		});
	});

	it('omits blank Language and Country rather than sending empty strings', async () => {
		server.tool('get_stream', () => toolResult({ name: 'a stream' }));

		await run({
			parameters: {
				resource: 'stream',
				operation: 'get',
				url: 'https://example.com/watch?v=1',
				options: { language: '   ', country: '' },
			},
		});

		// An untouched option must leave the server on its own default, not override it with "".
		assert.deepEqual(argumentsOf('get_stream'), { url: 'https://example.com/watch?v=1' });
	});

	it('does not repeat the language on get_more, which the page token carries', async () => {
		server
			.tool('search', () => toolResult({ items: [{ n: 1 }], nextPageToken: 'p2' }))
			.tool('get_more', () => toolResult({ items: [{ n: 2 }] }));

		await run({
			parameters: {
				resource: 'search',
				operation: 'search',
				serviceId: 0,
				query: 'lofi',
				contentFilters: [],
				sortFilters: [],
				returnAll: true,
				options: { language: 'en-GB' },
			},
		});

		assert.deepEqual(argumentsOf('get_more'), { pageToken: 'p2' });
	});

	it('returns a stream as a single item', async () => {
		server.tool('get_stream', () => toolResult({ name: 'a stream', duration: 42 }));

		const output = await run({
			parameters: {
				resource: 'stream',
				operation: 'get',
				url: '  https://example.com/watch?v=1  ',
			},
		});

		assert.deepEqual(output, [{ name: 'a stream', duration: 42 }]);
		// The URL is trimmed before it reaches the extractor.
		assert.deepEqual(argumentsOf('get_stream'), { url: 'https://example.com/watch?v=1' });
	});

	it('emits nothing when comments are disabled for a stream', async () => {
		server.tool('get_comments', () => toolResult({ commentsDisabled: true }));

		const output = await run({
			parameters: {
				resource: 'stream',
				operation: 'getComments',
				url: 'https://example.com/watch?v=1',
				returnAll: true,
			},
		});

		assert.deepEqual(output, []);
	});

	it('omits the kiosk id so the server picks its default kiosk', async () => {
		server.tool('get_kiosk', () => toolResult({ items: [{ name: 'trending' }] }));

		await run({
			parameters: {
				resource: 'kiosk',
				operation: 'getAll',
				serviceId: 0,
				kioskId: '',
				returnAll: true,
			},
		});

		assert.deepEqual(argumentsOf('get_kiosk'), { serviceId: 0 });
	});

	it('expands suggestions into one item each', async () => {
		server.tool('get_suggestions', () => toolResult({ suggestions: ['lofi beats', 'lofi jazz'] }));

		const output = await run({
			parameters: {
				resource: 'search',
				operation: 'getSuggestions',
				serviceId: 0,
				query: 'lofi',
			},
		});

		assert.deepEqual(output, [
			{ query: 'lofi', suggestion: 'lofi beats' },
			{ query: 'lofi', suggestion: 'lofi jazz' },
		]);
	});

	it('reuses one MCP session for every input item and closes it once', async () => {
		server.tool('get_stream', () => toolResult({ name: 'a stream' }));

		const output = await run({
			parameters: { resource: 'stream', operation: 'get', url: 'https://example.com/watch?v=1' },
			items: [{ json: {} }, { json: {} }, { json: {} }],
		});

		assert.equal(output.length, 3);
		assert.equal(server.calls.filter((call) => call === 'initialize').length, 1);
		assert.equal(server.calls.filter((call) => call === 'tools/call').length, 3);
		assert.equal(server.requests.filter((request) => request.method === 'DELETE').length, 1);
	});

	it('fails the workflow when a tool errors', async () => {
		server.tool('get_stream', () => toolError('Could not get any stream URL'));

		await assert.rejects(
			() =>
				run({
					parameters: { resource: 'stream', operation: 'get', url: 'https://example.com/x' },
				}),
			/Could not get any stream URL/,
		);
	});

	it('collects the error per item when Continue On Fail is set', async () => {
		server.tool('get_stream', () => toolError('Could not get any stream URL'));

		const output = await run({
			parameters: { resource: 'stream', operation: 'get', url: 'https://example.com/x' },
			items: [{ json: {} }, { json: {} }],
			continueOnFail: true,
		});

		assert.equal(output.length, 2);
		for (const item of output) {
			assert.match(String(item.error), /Could not get any stream URL/);
		}
		// The session is still torn down after a failing run.
		assert.equal(server.requests.filter((request) => request.method === 'DELETE').length, 1);
	});

	it('sends the access token as a bearer header when one is configured', async () => {
		server.tool('list_services', () => toolResult({ services: [] }));

		await run({
			parameters: { resource: 'service', operation: 'getAll' },
			accessToken: 'secret-token',
		});

		for (const request of server.requests) {
			assert.equal(request.headers.authorization, 'Bearer secret-token');
		}
	});
});
