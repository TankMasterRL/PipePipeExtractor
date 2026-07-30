import type { IExecuteFunctions, INodeExecutionData } from 'n8n-workflow';

import type { JsonObject } from '../../nodes/PipePipe/McpClient';
import { httpRequest } from './httpRequest';

export interface FakeExecuteOptions {
	endpoint: string;
	/** Node parameters, applied to every input item. */
	parameters: JsonObject;
	/** Input items; defaults to a single empty item. */
	items?: INodeExecutionData[];
	continueOnFail?: boolean;
	accessToken?: string;
}

/**
 * A stand-in for n8n's execution context, exposing just what the node uses so
 * `PipePipe.execute` can be driven directly in tests.
 */
export function fakeExecuteFunctions(options: FakeExecuteOptions): IExecuteFunctions {
	const items = options.items ?? [{ json: {} }];

	const context = {
		getInputData: () => items,
		getNode: () => ({ name: 'PipePipe', type: 'pipePipe', typeVersion: 1 }),
		continueOnFail: () => options.continueOnFail ?? false,
		getCredentials: async () => ({
			endpoint: options.endpoint,
			accessToken: options.accessToken ?? '',
		}),
		getNodeParameter: (name: string, _index: number, fallback?: unknown) => {
			const value = options.parameters[name];
			if (value !== undefined) {
				return value;
			}
			if (fallback !== undefined) {
				return fallback;
			}
			throw new Error(`Test did not provide the node parameter "${name}"`);
		},
		helpers: { httpRequest },
	};

	return context as unknown as IExecuteFunctions;
}
