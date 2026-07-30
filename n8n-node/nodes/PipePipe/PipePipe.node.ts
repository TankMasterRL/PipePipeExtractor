import {
	NodeConnectionTypes,
	NodeOperationError,
	type IDataObject,
	type IExecuteFunctions,
	type ILoadOptionsFunctions,
	type INodeExecutionData,
	type INodePropertyOptions,
	type INodeType,
	type INodeTypeDescription,
} from 'n8n-workflow';

import { McpClient, isJsonObject, type JsonObject, type McpHttpRequest } from './McpClient';
import { collectItems } from './Pagination';
import { PAGINATED_OPERATIONS, pipePipeProperties } from './PipePipeDescription';

type Context = IExecuteFunctions | ILoadOptionsFunctions;

/**
 * Opens an MCP session against the server configured in the node's credentials.
 *
 * n8n's `httpRequest` helper is passed through to the client so requests honour
 * the instance's proxy and TLS configuration.
 */
async function connect(context: Context): Promise<McpClient> {
	const credentials = await context.getCredentials('pipePipeExtractorApi');
	const endpoint = String(credentials.endpoint ?? '').trim();
	if (endpoint === '') {
		throw new NodeOperationError(
			context.getNode(),
			'No MCP endpoint is configured in the PipePipe Extractor credentials',
		);
	}

	const headers: Record<string, string> = {};
	const accessToken = String(credentials.accessToken ?? '').trim();
	if (accessToken !== '') {
		headers.Authorization = `Bearer ${accessToken}`;
	}

	const request: McpHttpRequest = async (options) => context.helpers.httpRequest(options);
	return McpClient.connect(request, { endpoint, headers });
}

/** Reads the services list, used by every load-options method. */
async function loadServices(context: ILoadOptionsFunctions): Promise<JsonObject[]> {
	const client = await connect(context);
	try {
		const result = await client.callTool('list_services');
		const services = result.services;
		return Array.isArray(services) ? services.filter(isJsonObject) : [];
	} finally {
		await client.close();
	}
}

/**
 * Resolves the service currently selected in the node's parameters, so filter
 * and kiosk dropdowns can be scoped to it.
 */
function selectedServiceId(context: ILoadOptionsFunctions): number | undefined {
	const raw = context.getCurrentNodeParameter('serviceId');
	const id = Number(raw);
	return Number.isFinite(id) ? id : undefined;
}

function filterOptions(service: JsonObject | undefined, key: string): INodePropertyOptions[] {
	const filters = service?.[key];
	if (!Array.isArray(filters)) {
		return [];
	}
	return filters.filter(isJsonObject).map((filter) => {
		const group = typeof filter.group === 'string' && filter.group !== '' ? filter.group : undefined;
		const name = String(filter.name ?? filter.id);
		return {
			name: group === undefined ? name : `${group}: ${name}`,
			value: Number(filter.id),
		};
	});
}

export class PipePipe implements INodeType {
	description: INodeTypeDescription = {
		displayName: 'PipePipe',
		name: 'pipePipe',
		icon: 'file:pipePipe.svg',
		group: ['input'],
		version: 1,
		subtitle: '={{$parameter["operation"] + ": " + $parameter["resource"]}}',
		description: 'Extract streams, channels, playlists and comments via PipePipe Extractor',
		defaults: {
			name: 'PipePipe',
		},
		inputs: [NodeConnectionTypes.Main],
		outputs: [NodeConnectionTypes.Main],
		usableAsTool: true,
		credentials: [
			{
				name: 'pipePipeExtractorApi',
				required: true,
			},
		],
		properties: pipePipeProperties,
	};

	methods = {
		loadOptions: {
			async getServices(this: ILoadOptionsFunctions): Promise<INodePropertyOptions[]> {
				return (await loadServices(this)).map((service) => ({
					name: String(service.name ?? service.id),
					value: Number(service.id),
				}));
			},

			async getContentFilters(this: ILoadOptionsFunctions): Promise<INodePropertyOptions[]> {
				const serviceId = selectedServiceId(this);
				const services = await loadServices(this);
				return filterOptions(
					services.find((service) => Number(service.id) === serviceId),
					'searchContentFilters',
				);
			},

			async getSortFilters(this: ILoadOptionsFunctions): Promise<INodePropertyOptions[]> {
				const serviceId = selectedServiceId(this);
				const services = await loadServices(this);
				return filterOptions(
					services.find((service) => Number(service.id) === serviceId),
					'searchSortFilters',
				);
			},

			async getKiosks(this: ILoadOptionsFunctions): Promise<INodePropertyOptions[]> {
				const serviceId = selectedServiceId(this);
				const services = await loadServices(this);
				const service = services.find((entry) => Number(entry.id) === serviceId);
				const kiosks = service?.kiosks;
				if (!Array.isArray(kiosks)) {
					return [];
				}
				const defaultKiosk = service?.defaultKiosk;
				return kiosks
					.filter((kiosk): kiosk is string => typeof kiosk === 'string')
					.map((kiosk) => ({
						name: kiosk === defaultKiosk ? `${kiosk} (default)` : kiosk,
						value: kiosk,
					}));
			},
		},
	};

	async execute(this: IExecuteFunctions): Promise<INodeExecutionData[][]> {
		const items = this.getInputData();
		const returnData: INodeExecutionData[] = [];

		let client: McpClient;
		try {
			client = await connect(this);
		} catch (error) {
			if (error instanceof NodeOperationError) {
				throw error;
			}
			throw new NodeOperationError(this.getNode(), error as Error, {
				description:
					'Could not reach the PipePipe Extractor MCP server. Check that it is running with "--transport http" and that the endpoint in the credentials points at it.',
			});
		}

		try {
			for (let i = 0; i < items.length; i++) {
				try {
					const results = await runOperation(this, client, i);
					returnData.push(
						...results.map((json) => ({
							json: json as IDataObject,
							pairedItem: { item: i },
						})),
					);
				} catch (error) {
					if (this.continueOnFail()) {
						returnData.push({
							json: { error: (error as Error).message },
							pairedItem: { item: i },
						});
						continue;
					}
					if (error instanceof NodeOperationError) {
						throw error;
					}
					throw new NodeOperationError(this.getNode(), error as Error, { itemIndex: i });
				}
			}
		} finally {
			await client.close();
		}

		return [returnData];
	}
}

/** Dispatches a single input item to the tool call(s) its operation needs. */
async function runOperation(
	context: IExecuteFunctions,
	client: McpClient,
	i: number,
): Promise<JsonObject[]> {
	const resource = context.getNodeParameter('resource', i) as string;
	const operation = context.getNodeParameter('operation', i) as string;
	const key = `${resource}:${operation}`;

	const limit = PAGINATED_OPERATIONS.includes(key) ? pageLimit(context, i) : undefined;
	const url = () => (context.getNodeParameter('url', i) as string).trim();
	const serviceId = () => Number(context.getNodeParameter('serviceId', i));

	switch (key) {
		case 'service:getAll': {
			const result = await client.callTool('list_services');
			return asObjects(result.services);
		}

		case 'search:search': {
			const result = await client.callTool('search', {
				serviceId: serviceId(),
				query: context.getNodeParameter('query', i) as string,
				contentFilters: numberList(context.getNodeParameter('contentFilters', i, [])),
				sortFilters: numberList(context.getNodeParameter('sortFilters', i, [])),
			});
			return await collectItems(client, result, limit);
		}

		case 'search:getSuggestions': {
			const query = context.getNodeParameter('query', i) as string;
			const result = await client.callTool('get_suggestions', {
				serviceId: serviceId(),
				query,
			});
			const suggestions = Array.isArray(result.suggestions) ? result.suggestions : [];
			return suggestions.map((suggestion) => ({ query, suggestion: String(suggestion) }));
		}

		case 'stream:get':
			return [await client.callTool('get_stream', { url: url() })];

		case 'stream:getComments': {
			const result = await client.callTool('get_comments', { url: url() });
			if (result.commentsSupported === false || result.commentsDisabled === true) {
				return [];
			}
			return await collectItems(client, result, limit);
		}

		case 'channel:get':
			return [await client.callTool('get_channel', { url: url() })];

		case 'channel:getTabItems': {
			const result = await client.callTool('get_channel_tab', {
				tabToken: (context.getNodeParameter('tabToken', i) as string).trim(),
			});
			return await collectItems(client, result, limit);
		}

		case 'channel:getFeed': {
			const result = await client.callTool('get_feed', {
				serviceId: serviceId(),
				url: url(),
			});
			return asObjects(result.items);
		}

		case 'playlist:get':
			return [await client.callTool('get_playlist', { url: url() })];

		case 'playlist:getItems': {
			const result = await client.callTool('get_playlist', { url: url() });
			return await collectItems(client, result, limit);
		}

		case 'kiosk:getAll': {
			const kioskId = (context.getNodeParameter('kioskId', i, '') as string).trim();
			const args: JsonObject = { serviceId: serviceId() };
			if (kioskId !== '') {
				args.kioskId = kioskId;
			}
			const result = await client.callTool('get_kiosk', args);
			return await collectItems(client, result, limit);
		}

		default:
			throw new NodeOperationError(
				context.getNode(),
				`The operation "${operation}" is not supported for resource "${resource}"`,
				{ itemIndex: i },
			);
	}
}

function pageLimit(context: IExecuteFunctions, i: number): number | undefined {
	const returnAll = context.getNodeParameter('returnAll', i, false) as boolean;
	return returnAll ? undefined : (context.getNodeParameter('limit', i, 50) as number);
}

function asObjects(value: unknown): JsonObject[] {
	return Array.isArray(value) ? value.filter(isJsonObject) : [];
}

function numberList(value: unknown): number[] {
	if (!Array.isArray(value)) {
		return [];
	}
	return value.map(Number).filter((entry) => Number.isFinite(entry));
}
