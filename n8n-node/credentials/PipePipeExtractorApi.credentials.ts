import type { ICredentialType, INodeProperties } from 'n8n-workflow';

/**
 * Connection details for a PipePipe Extractor MCP server.
 *
 * The server itself is unauthenticated, so only the endpoint is required; the
 * optional token covers the common case of running it behind an authenticating
 * reverse proxy.
 */
export class PipePipeExtractorApi implements ICredentialType {
	name = 'pipePipeExtractorApi';

	displayName = 'PipePipe Extractor MCP API';

	documentationUrl = 'https://github.com/TankMasterRL/PipePipeExtractor/tree/main/n8n-node';

	properties: INodeProperties[] = [
		{
			displayName: 'MCP Endpoint',
			name: 'endpoint',
			type: 'string',
			default: 'http://localhost:3000/mcp',
			required: true,
			placeholder: 'http://localhost:3000/mcp',
			description:
				'URL of the Streamable HTTP endpoint of a PipePipe Extractor MCP server, started with <code>./gradlew :mcp-server:run --args="--transport http --port 3000"</code>',
		},
		{
			displayName: 'Access Token',
			name: 'accessToken',
			type: 'string',
			typeOptions: { password: true },
			default: '',
			description:
				'Optional bearer token, for when the MCP server sits behind an authenticating proxy. Leave empty for a plain local server.',
		},
	];
}
