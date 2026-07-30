import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import { McpClient, McpError, McpToolError } from '../nodes/PipePipe/McpClient';
import { httpRequest } from './support/httpRequest';
import {
	StubMcpServer,
	textOnlyResult,
	toolError,
	toolResult,
	type StubOptions,
} from './support/stubServer';

/** Boots a stub server for one describe block and tears it down afterwards. */
function withServer(options: StubOptions = {}): () => StubMcpServer {
	const server = new StubMcpServer(options);
	before(async () => server.start());
	after(async () => server.stop());
	return () => server;
}

describe('McpClient handshake', () => {
	const server = withServer({ sessionId: 'session-abc', protocolVersion: '2025-03-26' });

	it('initializes, announces readiness, and echoes session and protocol headers', async () => {
		const stub = server();
		stub.tool('list_services', () => toolResult({ services: [{ id: 0, name: 'YouTube' }] }));

		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		const result = await client.callTool('list_services');
		await client.close();

		assert.deepEqual(result, { services: [{ id: 0, name: 'YouTube' }] });
		assert.deepEqual(stub.calls, ['initialize', 'notifications/initialized', 'tools/call']);

		const [initialize, initialized, toolCall] = stub.requests;
		assert.equal(initialize.headers['content-type'], 'application/json');
		assert.equal(initialize.headers.accept, 'application/json, text/event-stream');
		// The session id only exists after initialize, so it must not be sent on it.
		assert.equal(initialize.headers['mcp-session-id'], undefined);

		for (const request of [initialized, toolCall]) {
			assert.equal(request.headers['mcp-session-id'], 'session-abc');
			// The negotiated version must be echoed, not the one the client asked for.
			assert.equal(request.headers['mcp-protocol-version'], '2025-03-26');
		}

		const deleteRequest = stub.requests.at(-1)!;
		assert.equal(deleteRequest.method, 'DELETE');
		assert.equal(deleteRequest.headers['mcp-session-id'], 'session-abc');
	});
});

describe('McpClient without a session id', () => {
	const server = withServer({ protocolVersion: null });

	it('falls back to the specified default protocol version and skips teardown', async () => {
		const stub = server();
		stub.tool('get_stream', () => toolResult({ name: 'a stream' }));

		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		await client.callTool('get_stream', { url: 'https://example.com/watch?v=1' });
		await client.close();

		const toolCall = stub.requests[2];
		assert.equal(toolCall.headers['mcp-protocol-version'], '2025-03-26');
		assert.equal(toolCall.headers['mcp-session-id'], undefined);
		// Without a session there is nothing to terminate.
		assert.ok(!stub.requests.some((request) => request.method === 'DELETE'));
	});
});

describe('McpClient over SSE responses', () => {
	const server = withServer({ responseStyle: 'sse', sessionId: 'sse-session' });

	it('parses the JSON-RPC message out of an event stream', async () => {
		const stub = server();
		stub.tool('search', () => toolResult({ items: [{ name: 'first' }], nextPageToken: 'tok' }));

		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		const result = await client.callTool('search', { serviceId: 0, query: 'lofi' });
		await client.close();

		assert.deepEqual(result, { items: [{ name: 'first' }], nextPageToken: 'tok' });
	});
});

describe('McpClient result handling', () => {
	const server = withServer({ sessionId: 's' });

	it('falls back to the text content block when structuredContent is absent', async () => {
		const stub = server();
		stub.tool('get_playlist', () => textOnlyResult({ name: 'a playlist', streamCount: 3 }));

		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		const result = await client.callTool('get_playlist', { url: 'https://example.com/list' });
		await client.close();

		assert.deepEqual(result, { name: 'a playlist', streamCount: 3 });
	});

	it('surfaces a failing tool as McpToolError carrying the server message', async () => {
		const stub = server();
		stub.tool('get_stream', () => toolError('Could not get any stream URL'));

		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		await assert.rejects(
			() => client.callTool('get_stream', { url: 'https://example.com/broken' }),
			(error: Error) => {
				assert.ok(error instanceof McpToolError);
				assert.match(error.message, /Could not get any stream URL/);
				return true;
			},
		);
		await client.close();
	});

	it('surfaces a JSON-RPC error as McpError', async () => {
		const stub = server();
		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		await assert.rejects(
			() => client.callTool('no_such_tool'),
			(error: Error) => {
				assert.ok(error instanceof McpError);
				assert.match(error.message, /Unknown tool: no_such_tool/);
				assert.match(error.message, /code -32602/);
				return true;
			},
		);
		await client.close();
	});
});

describe('McpClient teardown resilience', () => {
	const server = withServer({ sessionId: 's', deleteStatus: 405 });

	it('ignores a server that refuses session termination', async () => {
		const stub = server();
		const client = await McpClient.connect(httpRequest, { endpoint: stub.url });
		await client.close();
		assert.ok(stub.requests.some((request) => request.method === 'DELETE'));
	});
});
