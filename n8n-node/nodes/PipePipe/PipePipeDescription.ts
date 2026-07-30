import type { INodeProperties } from 'n8n-workflow';

/** Operations that page through a list and therefore offer Return All / Limit. */
export const PAGINATED_OPERATIONS: ReadonlyArray<string> = [
	'channel:getTabItems',
	'kiosk:getAll',
	'playlist:getItems',
	'search:search',
	'stream:getComments',
];

/**
 * Shown for every operation that identifies its service by id rather than by
 * URL. `show` conditions are ANDed, so listing both the resources and their
 * operations is enough here: the only channel operation named in it is Get
 * Feed, and no other listed resource has an operation of the same name.
 */
const serviceIdProperty: INodeProperties = {
	displayName: 'Service Name or ID',
	name: 'serviceId',
	type: 'options',
	typeOptions: { loadOptionsMethod: 'getServices' },
	default: 0,
	required: true,
	description:
		'Streaming service to query. Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>.',
	displayOptions: {
		show: {
			resource: ['channel', 'kiosk', 'search'],
			operation: ['getAll', 'getFeed', 'getSuggestions', 'search'],
		},
	},
};

const urlProperty = (
	resource: string,
	operations: string[],
	placeholder: string,
	description: string,
): INodeProperties => ({
	displayName: 'URL',
	name: 'url',
	type: 'string',
	default: '',
	required: true,
	placeholder,
	description,
	displayOptions: {
		show: {
			resource: [resource],
			operation: operations,
		},
	},
});

export const pipePipeProperties: INodeProperties[] = [
	{
		displayName: 'Resource',
		name: 'resource',
		type: 'options',
		noDataExpression: true,
		default: 'search',
		options: [
			{ name: 'Channel', value: 'channel' },
			{ name: 'Kiosk', value: 'kiosk' },
			{ name: 'Playlist', value: 'playlist' },
			{ name: 'Search', value: 'search' },
			{ name: 'Service', value: 'service' },
			{ name: 'Stream', value: 'stream' },
		],
	},

	// ----------------------------------- operations -----------------------------------
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		default: 'get',
		displayOptions: { show: { resource: ['channel'] } },
		options: [
			{
				name: 'Get',
				value: 'get',
				action: 'Get a channel',
				description: 'Get a channel’s metadata and the tokens of its tabs',
			},
			{
				name: 'Get Feed',
				value: 'getFeed',
				action: 'Get a channel feed',
				description: 'Get a channel’s lightweight feed, where the service provides one',
			},
			{
				name: 'Get Tab Items',
				value: 'getTabItems',
				action: 'Get channel tab items',
				description: 'Get the items of a channel tab, using a tab token from Channel → Get',
			},
		],
	},
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		default: 'getAll',
		displayOptions: { show: { resource: ['kiosk'] } },
		options: [
			{
				name: 'Get Many',
				value: 'getAll',
				action: 'Get many kiosk items',
				description: 'Get the items of a kiosk, such as trending or charts',
			},
		],
	},
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		default: 'get',
		displayOptions: { show: { resource: ['playlist'] } },
		options: [
			{
				name: 'Get',
				value: 'get',
				action: 'Get a playlist',
				description: 'Get a playlist’s metadata and its first page of streams',
			},
			{
				name: 'Get Items',
				value: 'getItems',
				action: 'Get playlist items',
				description: 'Get the streams of a playlist, one item per stream',
			},
		],
	},
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		default: 'search',
		displayOptions: { show: { resource: ['search'] } },
		options: [
			{
				name: 'Get Suggestions',
				value: 'getSuggestions',
				action: 'Get search suggestions',
				description: 'Get autocomplete suggestions for a partial query',
			},
			{
				name: 'Search',
				value: 'search',
				action: 'Search a service',
				description: 'Search a service and return the matching items',
			},
		],
	},
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		default: 'getAll',
		displayOptions: { show: { resource: ['service'] } },
		options: [
			{
				name: 'Get Many',
				value: 'getAll',
				action: 'Get many services',
				description: 'List the supported services with their IDs, filters and kiosks',
			},
		],
	},
	{
		displayName: 'Operation',
		name: 'operation',
		type: 'options',
		noDataExpression: true,
		default: 'get',
		displayOptions: { show: { resource: ['stream'] } },
		options: [
			{
				name: 'Get',
				value: 'get',
				action: 'Get a stream',
				description: 'Get a stream’s metadata and its playable audio/video URLs',
			},
			{
				name: 'Get Comments',
				value: 'getComments',
				action: 'Get stream comments',
				description: 'Get the comments of a stream, one item per comment',
			},
		],
	},

	// ------------------------------------ fields -------------------------------------
	serviceIdProperty,
	{
		displayName: 'Query',
		name: 'query',
		type: 'string',
		default: '',
		required: true,
		placeholder: 'lofi hip hop',
		description: 'The search query',
		displayOptions: {
			show: {
				resource: ['search'],
				operation: ['search', 'getSuggestions'],
			},
		},
	},
	{
		displayName: 'Content Filter Names or IDs',
		name: 'contentFilters',
		type: 'multiOptions',
		typeOptions: {
			loadOptionsMethod: 'getContentFilters',
			loadOptionsDependsOn: ['serviceId'],
		},
		default: [],
		description:
			'Restrict results to these content types. Choose from the list, or specify IDs using an <a href="https://docs.n8n.io/code/expressions/">expression</a>.',
		displayOptions: {
			show: {
				resource: ['search'],
				operation: ['search'],
			},
		},
	},
	{
		displayName: 'Sort Filter Names or IDs',
		name: 'sortFilters',
		type: 'multiOptions',
		typeOptions: {
			loadOptionsMethod: 'getSortFilters',
			loadOptionsDependsOn: ['serviceId'],
		},
		default: [],
		description:
			'Sort or further filter the results. Choose from the list, or specify IDs using an <a href="https://docs.n8n.io/code/expressions/">expression</a>.',
		displayOptions: {
			show: {
				resource: ['search'],
				operation: ['search'],
			},
		},
	},
	{
		displayName: 'Kiosk Name or ID',
		name: 'kioskId',
		type: 'options',
		typeOptions: {
			loadOptionsMethod: 'getKiosks',
			loadOptionsDependsOn: ['serviceId'],
		},
		default: '',
		description:
			'Kiosk to fetch; leave empty for the service’s default kiosk. Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>.',
		displayOptions: {
			show: {
				resource: ['kiosk'],
				operation: ['getAll'],
			},
		},
	},
	urlProperty(
		'stream',
		['get', 'getComments'],
		'https://www.youtube.com/watch?v=…',
		'URL of the stream',
	),
	urlProperty('channel', ['get', 'getFeed'], 'https://www.youtube.com/@…', 'URL of the channel'),
	urlProperty(
		'playlist',
		['get', 'getItems'],
		'https://www.youtube.com/playlist?list=…',
		'URL of the playlist',
	),
	{
		displayName: 'Tab Token',
		name: 'tabToken',
		type: 'string',
		default: '',
		required: true,
		description: 'A tab token returned in the <code>tabs</code> array of a Channel → Get result',
		displayOptions: {
			show: {
				resource: ['channel'],
				operation: ['getTabItems'],
			},
		},
	},

	// -------------------------------- pagination -------------------------------------
	{
		displayName: 'Return All',
		name: 'returnAll',
		type: 'boolean',
		default: false,
		description: 'Whether to return all results or only up to a given limit',
		displayOptions: {
			show: {
				resource: ['channel', 'kiosk', 'playlist', 'search', 'stream'],
				operation: ['getTabItems', 'getAll', 'getItems', 'search', 'getComments'],
			},
		},
	},
	{
		displayName: 'Limit',
		name: 'limit',
		type: 'number',
		typeOptions: { minValue: 1 },
		default: 50,
		description: 'Max number of results to return',
		displayOptions: {
			show: {
				resource: ['channel', 'kiosk', 'playlist', 'search', 'stream'],
				operation: ['getTabItems', 'getAll', 'getItems', 'search', 'getComments'],
				returnAll: [false],
			},
		},
	},
];
