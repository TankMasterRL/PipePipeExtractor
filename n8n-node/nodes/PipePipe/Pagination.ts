import { isJsonObject, type JsonObject } from './McpClient';

export interface PageSource {
	callTool(name: string, args?: JsonObject): Promise<JsonObject>;
}

/**
 * The extractor omits empty lists rather than sending `[]`, so a page may carry
 * no `items` key at all.
 */
export function itemsOf(page: JsonObject): JsonObject[] {
	const items = page.items;
	return Array.isArray(items) ? items.filter(isJsonObject) : [];
}

export function nextPageTokenOf(page: JsonObject): string | undefined {
	const token = page.nextPageToken;
	return typeof token === 'string' && token !== '' ? token : undefined;
}

/**
 * Walks a paginated result, following `nextPageToken`s through the `get_more`
 * tool until the list is exhausted or `limit` items have been collected.
 *
 * A server that keeps handing back the same token would page forever, so
 * pagination stops as soon as a page fails to advance the cursor.
 *
 * @param source the MCP client used to fetch further pages
 * @param first the first page, as returned by the operation's own tool
 * @param limit maximum number of items to collect, or undefined for all of them
 */
export async function collectItems(
	source: PageSource,
	first: JsonObject,
	limit?: number,
): Promise<JsonObject[]> {
	const collected = itemsOf(first);
	let token = nextPageTokenOf(first);

	while (token !== undefined && (limit === undefined || collected.length < limit)) {
		const page = await source.callTool('get_more', { pageToken: token });
		collected.push(...itemsOf(page));

		const next = nextPageTokenOf(page);
		if (next === token) {
			break;
		}
		token = next;
	}

	return limit === undefined ? collected : collected.slice(0, limit);
}
