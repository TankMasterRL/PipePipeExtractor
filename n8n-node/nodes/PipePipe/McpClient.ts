/**
 * A minimal MCP (Model Context Protocol) client for the Streamable HTTP transport.
 *
 * The PipePipe Extractor MCP server (`:mcp-server`, started with
 * `--transport http`) speaks JSON-RPC 2.0 over a single HTTP endpoint. This
 * client implements only what the node needs: the initialize handshake, the
 * session and protocol-version headers, `tools/call`, and session teardown.
 *
 * It deliberately has no runtime dependency on an MCP SDK — requests go through
 * whatever `httpRequest` helper the caller supplies, so n8n's own HTTP helper
 * (with its proxy handling and timeouts) is used at runtime and a plain stub can
 * be used in tests.
 */

/** The protocol revision this client asks for during initialization. */
const CLIENT_PROTOCOL_VERSION = '2025-06-18';

/** Fallback the spec prescribes when a server reports no version of its own. */
const FALLBACK_PROTOCOL_VERSION = '2025-03-26';

const CLIENT_NAME = 'n8n-nodes-pipepipe';
const CLIENT_VERSION = '0.1.0';

export interface JsonObject {
	[key: string]: unknown;
}

/**
 * The subset of an n8n execution context this client relies on. Declaring it
 * structurally (rather than importing `IExecuteFunctions`) keeps the client
 * usable from execute, loadOptions and tests alike.
 */
export interface McpHttpRequest {
	(options: {
		method: 'POST' | 'DELETE';
		url: string;
		headers: Record<string, string>;
		body?: string;
		json: false;
		returnFullResponse: true;
	}): Promise<unknown>;
}

export interface McpClientOptions {
	/** Full URL of the MCP endpoint, e.g. `http://localhost:3000/mcp`. */
	endpoint: string;
	/** Extra headers to send on every request (e.g. an Authorization header). */
	headers?: Record<string, string>;
}

/** Raised for any protocol- or transport-level failure. */
export class McpError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'McpError';
	}
}

/** Raised when a tool ran but reported a failure (`isError: true`). */
export class McpToolError extends Error {
	constructor(
		message: string,
		readonly toolName: string,
	) {
		super(message);
		this.name = 'McpToolError';
	}
}

interface JsonRpcMessage {
	jsonrpc?: string;
	id?: number | string | null;
	result?: JsonObject;
	error?: { code?: number; message?: string; data?: unknown };
}

interface FullResponse {
	body?: unknown;
	headers?: Record<string, unknown>;
	statusCode?: number;
}

export class McpClient {
	private nextId = 1;

	private constructor(
		private readonly request: McpHttpRequest,
		private readonly endpoint: string,
		private readonly extraHeaders: Record<string, string>,
		private readonly sessionId: string | undefined,
		private readonly protocolVersion: string,
	) {}

	/**
	 * Performs the initialize handshake and returns a ready-to-use client.
	 *
	 * Per the Streamable HTTP transport, the server may hand out a session id in
	 * the `Mcp-Session-Id` response header; when it does, every later request
	 * (including the session-terminating DELETE) has to echo it back.
	 */
	static async connect(request: McpHttpRequest, options: McpClientOptions): Promise<McpClient> {
		const extraHeaders = options.headers ?? {};
		const id = 1;
		const response = (await request({
			method: 'POST',
			url: options.endpoint,
			headers: {
				...extraHeaders,
				'Content-Type': 'application/json',
				Accept: 'application/json, text/event-stream',
			},
			body: JSON.stringify({
				jsonrpc: '2.0',
				id,
				method: 'initialize',
				params: {
					protocolVersion: CLIENT_PROTOCOL_VERSION,
					capabilities: {},
					clientInfo: { name: CLIENT_NAME, version: CLIENT_VERSION },
				},
			}),
			json: false,
			returnFullResponse: true,
		})) as FullResponse;

		const message = extractMessage(response, id);
		if (message?.error) {
			throw new McpError(`MCP initialize failed: ${describeRpcError(message.error)}`);
		}
		if (message?.result === undefined) {
			throw new McpError(
				'MCP initialize returned no result. Is the URL the MCP endpoint of a PipePipe ' +
					'Extractor server started with "--transport http"?',
			);
		}

		const sessionId = headerValue(response.headers, 'mcp-session-id');
		const negotiated = message.result.protocolVersion;
		const protocolVersion =
			typeof negotiated === 'string' && negotiated !== '' ? negotiated : FALLBACK_PROTOCOL_VERSION;

		const client = new McpClient(
			request,
			options.endpoint,
			extraHeaders,
			sessionId,
			protocolVersion,
		);
		client.nextId = id + 1;
		await client.sendInitialized();
		return client;
	}

	/**
	 * Calls an MCP tool and returns its result payload as a plain object.
	 *
	 * The PipePipe server answers with both a JSON text block and
	 * `structuredContent`; the latter is preferred, with the text block parsed as
	 * a fallback for servers that omit it.
	 */
	async callTool(name: string, args: JsonObject = {}): Promise<JsonObject> {
		const message = await this.rpc('tools/call', { name, arguments: args });
		if (message.error) {
			throw new McpError(`MCP tool "${name}" failed: ${describeRpcError(message.error)}`);
		}

		const result = message.result ?? {};
		if (result.isError === true) {
			throw new McpToolError(textContent(result) ?? `Tool "${name}" reported an error`, name);
		}

		const structured = result.structuredContent;
		if (isJsonObject(structured)) {
			return structured;
		}

		const text = textContent(result);
		if (text === undefined) {
			throw new McpError(`MCP tool "${name}" returned no content`);
		}
		try {
			const parsed: unknown = JSON.parse(text);
			if (!isJsonObject(parsed)) {
				throw new McpError(`MCP tool "${name}" returned a non-object payload`);
			}
			return parsed;
		} catch (error) {
			if (error instanceof McpError) {
				throw error;
			}
			throw new McpError(`MCP tool "${name}" returned content that is not valid JSON: ${text}`);
		}
	}

	/**
	 * Terminates the server-side session. Servers are allowed to refuse the
	 * DELETE (405) or to run without sessions at all, so failures are ignored:
	 * this is cleanup, never something a workflow should fail on.
	 */
	async close(): Promise<void> {
		if (this.sessionId === undefined) {
			return;
		}
		try {
			await this.request({
				method: 'DELETE',
				url: this.endpoint,
				headers: this.headers(),
				json: false,
				returnFullResponse: true,
			});
		} catch {
			// Best-effort teardown.
		}
	}

	private async sendInitialized(): Promise<void> {
		await this.request({
			method: 'POST',
			url: this.endpoint,
			headers: {
				...this.headers(),
				'Content-Type': 'application/json',
				Accept: 'application/json, text/event-stream',
			},
			body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
			json: false,
			returnFullResponse: true,
		});
	}

	private async rpc(method: string, params: JsonObject): Promise<JsonRpcMessage> {
		const id = this.nextId++;
		const response = (await this.request({
			method: 'POST',
			url: this.endpoint,
			headers: {
				...this.headers(),
				'Content-Type': 'application/json',
				Accept: 'application/json, text/event-stream',
			},
			body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
			json: false,
			returnFullResponse: true,
		})) as FullResponse;

		const message = extractMessage(response, id);
		if (message === undefined) {
			throw new McpError(`MCP server returned no JSON-RPC response for "${method}"`);
		}
		return message;
	}

	private headers(): Record<string, string> {
		const headers: Record<string, string> = {
			...this.extraHeaders,
			'MCP-Protocol-Version': this.protocolVersion,
		};
		if (this.sessionId !== undefined) {
			headers['Mcp-Session-Id'] = this.sessionId;
		}
		return headers;
	}
}

/**
 * Pulls the JSON-RPC message matching `id` out of a response that may be a JSON
 * body, an SSE stream, or a batch of either.
 */
function extractMessage(response: FullResponse, id: number): JsonRpcMessage | undefined {
	const messages = collectMessages(response);
	const matching = messages.find((message) => message.id === id);
	if (matching !== undefined) {
		return matching;
	}
	// Fall back to any message carrying a payload, for servers that do not echo
	// the request id on errors.
	return messages.find((message) => message.result !== undefined || message.error !== undefined);
}

function collectMessages(response: FullResponse): JsonRpcMessage[] {
	const body = response.body;
	if (body === undefined || body === null || body === '') {
		return [];
	}

	// n8n's helper may have parsed the body already, depending on the response
	// content type.
	if (typeof body === 'object') {
		return toMessages(body);
	}
	if (typeof body !== 'string') {
		return [];
	}

	const contentType = (headerValue(response.headers, 'content-type') ?? '').toLowerCase();
	if (contentType.includes('text/event-stream')) {
		return parseSse(body);
	}

	try {
		return toMessages(JSON.parse(body));
	} catch {
		// Some servers omit the content type on SSE responses; retry as a stream
		// before giving up.
		const fromStream = parseSse(body);
		if (fromStream.length > 0) {
			return fromStream;
		}
		throw new McpError(`MCP server returned a body that is not valid JSON: ${truncate(body)}`);
	}
}

/**
 * Parses an SSE body into its JSON-RPC messages. Events are separated by a blank
 * line; a single event's `data:` lines are joined with newlines, and one leading
 * space after the colon is stripped, per the SSE specification.
 */
function parseSse(raw: string): JsonRpcMessage[] {
	const messages: JsonRpcMessage[] = [];
	for (const event of raw.split(/\r?\n\r?\n/)) {
		const data = event
			.split(/\r?\n/)
			.filter((line) => line.startsWith('data:'))
			.map((line) => line.slice('data:'.length).replace(/^ /, ''))
			.join('\n');
		if (data === '') {
			continue;
		}
		try {
			messages.push(...toMessages(JSON.parse(data)));
		} catch {
			// Ignore non-JSON events (comments, keep-alives).
		}
	}
	return messages;
}

function toMessages(parsed: unknown): JsonRpcMessage[] {
	if (Array.isArray(parsed)) {
		return parsed.filter(isJsonObject) as JsonRpcMessage[];
	}
	return isJsonObject(parsed) ? [parsed as JsonRpcMessage] : [];
}

/** Reads a header case-insensitively, tolerating array-valued headers. */
function headerValue(
	headers: Record<string, unknown> | undefined,
	name: string,
): string | undefined {
	if (headers === undefined || headers === null) {
		return undefined;
	}
	const wanted = name.toLowerCase();
	for (const [key, value] of Object.entries(headers)) {
		if (key.toLowerCase() !== wanted) {
			continue;
		}
		const resolved = Array.isArray(value) ? value[0] : value;
		if (typeof resolved === 'string' && resolved !== '') {
			return resolved;
		}
		if (typeof resolved === 'number') {
			return String(resolved);
		}
	}
	return undefined;
}

function textContent(result: JsonObject): string | undefined {
	const content = result.content;
	if (!Array.isArray(content)) {
		return undefined;
	}
	const texts = content
		.filter(isJsonObject)
		.filter((block) => block.type === 'text' && typeof block.text === 'string')
		.map((block) => block.text as string);
	return texts.length > 0 ? texts.join('\n') : undefined;
}

function describeRpcError(error: { code?: number; message?: string }): string {
	const message = error.message ?? 'unknown error';
	return error.code === undefined ? message : `${message} (code ${error.code})`;
}

function truncate(value: string, max = 200): string {
	return value.length <= max ? value : `${value.slice(0, max)}…`;
}

export function isJsonObject(value: unknown): value is JsonObject {
	return typeof value === 'object' && value !== null && !Array.isArray(value);
}
