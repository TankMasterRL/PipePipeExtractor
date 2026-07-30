import type { McpHttpRequest } from '../../nodes/PipePipe/McpClient';

/**
 * Stands in for n8n's `helpers.httpRequest`, matching the behaviour the client
 * relies on: with `returnFullResponse` it resolves to `{ body, headers,
 * statusCode }`, with `json: false` the body stays a string, header names are
 * lower-cased, and a non-2xx status rejects.
 */
export const httpRequest: McpHttpRequest = async (options) => {
	const response = await fetch(options.url, {
		method: options.method,
		headers: options.headers,
		body: options.body,
	});

	const body = await response.text();
	const headers: Record<string, string> = {};
	response.headers.forEach((value, key) => {
		headers[key.toLowerCase()] = value;
	});

	if (!response.ok) {
		throw new Error(`The service responded with status ${response.status}: ${body}`);
	}

	return { body, headers, statusCode: response.status };
};
