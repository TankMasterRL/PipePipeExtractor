import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { AddressInfo } from 'node:net';

import type { JsonObject } from '../../nodes/PipePipe/McpClient';

export interface RecordedRequest {
	method: string;
	headers: Record<string, string | string[] | undefined>;
	body: JsonObject | undefined;
}

export type ToolHandler = (args: JsonObject) => JsonObject;

export interface StubOptions {
	/** How tool/initialize responses are framed. */
	responseStyle?: 'json' | 'sse';
	/** Session id handed out on initialize; omit to run without sessions. */
	sessionId?: string;
	/** Protocol version reported by the server on initialize. */
	protocolVersion?: string | null;
	/** Status code returned for DELETE. */
	deleteStatus?: number;
}

/**
 * A minimal MCP server over Streamable HTTP, used to exercise the client's
 * protocol handling without any network access.
 */
export class StubMcpServer {
	readonly requests: RecordedRequest[] = [];

	private readonly tools = new Map<string, ToolHandler>();
	private server?: Server;
	private port = 0;

	constructor(private readonly options: StubOptions = {}) {}

	tool(name: string, handler: ToolHandler): this {
		this.tools.set(name, handler);
		return this;
	}

	get url(): string {
		return `http://127.0.0.1:${this.port}/mcp`;
	}

	/** Requests that carried a JSON-RPC method, in arrival order. */
	get calls(): string[] {
		return this.requests
			.map((request) => request.body?.method)
			.filter((method): method is string => typeof method === 'string');
	}

	async start(): Promise<void> {
		this.server = createServer((request, response) => {
			void this.handle(request, response);
		});
		await new Promise<void>((resolve) => this.server!.listen(0, '127.0.0.1', resolve));
		this.port = (this.server!.address() as AddressInfo).port;
	}

	async stop(): Promise<void> {
		if (this.server === undefined) {
			return;
		}
		await new Promise<void>((resolve, reject) =>
			this.server!.close((error) => (error ? reject(error) : resolve())),
		);
		this.server = undefined;
	}

	private async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
		const raw = await readBody(request);
		let body: JsonObject | undefined;
		try {
			body = raw === '' ? undefined : (JSON.parse(raw) as JsonObject);
		} catch {
			body = undefined;
		}
		this.requests.push({ method: request.method ?? '', headers: request.headers, body });

		if (request.method === 'DELETE') {
			response.writeHead(this.options.deleteStatus ?? 200).end();
			return;
		}

		if (body === undefined) {
			response.writeHead(400).end();
			return;
		}

		// Notifications carry no id and expect no response body.
		if (body.id === undefined) {
			response.writeHead(202).end();
			return;
		}

		const message = this.respondTo(body);
		this.send(response, message, request.method === 'POST' && body.method === 'initialize');
	}

	private respondTo(body: JsonObject): JsonObject {
		const id = body.id as number;

		if (body.method === 'initialize') {
			const result: JsonObject = {
				capabilities: { tools: {} },
				serverInfo: { name: 'stub', version: '0.0.1' },
			};
			if (this.options.protocolVersion !== null) {
				result.protocolVersion = this.options.protocolVersion ?? '2025-03-26';
			}
			return { jsonrpc: '2.0', id, result };
		}

		if (body.method === 'tools/call') {
			const params = (body.params ?? {}) as JsonObject;
			const name = String(params.name);
			const handler = this.tools.get(name);
			if (handler === undefined) {
				return {
					jsonrpc: '2.0',
					id,
					error: { code: -32602, message: `Unknown tool: ${name}` },
				};
			}
			const payload = handler((params.arguments ?? {}) as JsonObject);
			return { jsonrpc: '2.0', id, result: payload };
		}

		return {
			jsonrpc: '2.0',
			id,
			error: { code: -32601, message: `Method not found: ${String(body.method)}` },
		};
	}

	private send(response: ServerResponse, message: JsonObject, isInitialize: boolean): void {
		const headers: Record<string, string> = {};
		if (isInitialize && this.options.sessionId !== undefined) {
			headers['Mcp-Session-Id'] = this.options.sessionId;
		}

		if ((this.options.responseStyle ?? 'json') === 'sse') {
			response.writeHead(200, { ...headers, 'Content-Type': 'text/event-stream' });
			// Include a comment line and a split payload, as a real SSE stream may.
			response.end(`: keep-alive\n\nevent: message\ndata: ${JSON.stringify(message)}\n\n`);
			return;
		}

		response.writeHead(200, { ...headers, 'Content-Type': 'application/json' });
		response.end(JSON.stringify(message));
	}
}

/** Wraps a tool payload the way the PipePipe MCP server does. */
export function toolResult(payload: JsonObject): JsonObject {
	return {
		content: [{ type: 'text', text: JSON.stringify(payload) }],
		structuredContent: payload,
		isError: false,
	};
}

/** Wraps a tool payload as text only, for servers that omit structuredContent. */
export function textOnlyResult(payload: JsonObject): JsonObject {
	return {
		content: [{ type: 'text', text: JSON.stringify(payload) }],
		isError: false,
	};
}

/** Mirrors the error shape the PipePipe MCP server returns for a failed tool. */
export function toolError(message: string): JsonObject {
	return {
		content: [{ type: 'text', text: `Error: ${message}` }],
		isError: true,
	};
}

async function readBody(request: IncomingMessage): Promise<string> {
	const chunks: Buffer[] = [];
	for await (const chunk of request) {
		chunks.push(chunk as Buffer);
	}
	return Buffer.concat(chunks).toString('utf8');
}
