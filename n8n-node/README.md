# n8n-nodes-pipepipe

An [n8n](https://n8n.io) community node for
[PipePipe Extractor](https://github.com/TankMasterRL/PipePipeExtractor). It lets a
workflow search streaming services and pull stream, channel, playlist, comment and
kiosk data.

The node talks to the repository's `:mcp-server` module over the
[Model Context Protocol](https://modelcontextprotocol.io) Streamable HTTP transport,
so all extraction runs in the Java extractor and the node stays a thin client. It has
no runtime npm dependencies — the MCP client is implemented on top of n8n's own HTTP
helper, so proxy and TLS settings of the n8n instance apply.

## Prerequisites

Start the extractor's MCP server with the HTTP transport:

```sh
./gradlew :mcp-server:run --args="--transport http --port 3000"
```

Optional `--language <lang>` and `--country <country>` flags set the extractor's
localization and content country.

## Installation

In n8n, go to **Settings → Community nodes → Install** and enter
`n8n-nodes-pipepipe`.

To run it from a checkout instead:

```sh
cd n8n-node
npm install
npm run build
npm link

# in your n8n custom-extensions directory (~/.n8n/custom by default)
npm link n8n-nodes-pipepipe
```

Restart n8n afterwards so it picks the node up.

## Credentials

Create a **PipePipe Extractor MCP API** credential:

| Field | Description |
| --- | --- |
| MCP Endpoint | URL of the server's Streamable HTTP endpoint, e.g. `http://localhost:3000/mcp` |
| Access Token | Optional bearer token, for when the server sits behind an authenticating proxy. The server itself is unauthenticated. |

## Operations

| Resource | Operation | Output |
| --- | --- | --- |
| Service | Get Many | One item per supported service, with its id, search filters and kiosks |
| Search | Search | One item per result |
| Search | Get Suggestions | One item per autocomplete suggestion |
| Stream | Get | A single item: metadata plus playable audio/video URLs |
| Stream | Get Comments | One item per comment |
| Channel | Get | A single item: metadata plus a token per tab |
| Channel | Get Tab Items | One item per entry of a tab |
| Channel | Get Feed | One item per feed entry |
| Playlist | Get | A single item: metadata plus the first page of streams |
| Playlist | Get Items | One item per stream |
| Kiosk | Get Many | One item per kiosk entry, e.g. trending or charts |

Service, kiosk and search-filter dropdowns are populated from the server's
`list_services` tool, so the ids always match the running extractor.

To page through a channel, use **Channel → Get** first and pass one of the
`tabs[].token` values from its output to **Channel → Get Tab Items**.

### Pagination

Operations that return lists offer **Return All** and **Limit**. The node follows the
server's `nextPageToken` cursors for you and stops as soon as the limit is reached, so
a small limit does not fetch more pages than it needs. Paging also stops if the server
returns a cursor that does not advance.

### Errors

An extraction failure (an unsupported URL, an age-restricted stream, a service
blocking the request) fails the item. Enable **Continue On Fail** on the node to emit
`{ "error": "…" }` for the offending item and keep the workflow running.

Streams whose comments are unsupported or disabled produce no items rather than an
error.

## Development

```sh
npm install
npm run typecheck   # tsc --noEmit
npm run build       # compile to dist/ and copy the icon and codex file
npm test            # build, then run the offline test suite
```

The tests are offline: they run the real node and MCP client against a stub MCP server
on localhost that speaks the same JSON-RPC over Streamable HTTP, covering the
initialize handshake, session and protocol-version headers, JSON and SSE framing,
pagination and error mapping. No network access and no running Java server is needed.

## License

GPL-3.0, matching the rest of the repository.
