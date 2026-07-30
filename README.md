## README

The extractor of [PipePipe](https://codeberg.org/NullPointerException/PipePipe)

## MCP server

The `:mcp-server` module exposes PipePipe Extractor's public API as a
[Model Context Protocol](https://modelcontextprotocol.io) server, so an MCP client
(e.g. an LLM agent) can search services and fetch stream, channel, playlist, comment,
kiosk and feed data through a set of tools.

Run it with Gradle:

```sh
# stdio transport (default) — for clients that spawn the server as a subprocess
./gradlew :mcp-server:run

# Streamable HTTP on http://localhost:3000/mcp
./gradlew :mcp-server:run --args="--transport http --port 3000"

# HTTP+SSE on http://localhost:3000/sse
./gradlew :mcp-server:run --args="--transport sse --port 3000"
```

Optional `--language <lang>` and `--country <country>` flags set the extractor's
localization and content country.

Tools exposed: `list_services`, `get_suggestions`, `search`, `get_stream`,
`get_channel`, `get_channel_tab`, `get_playlist`, `get_comments`, `get_kiosk`,
`get_feed`, and `get_more` (for paginating any result via its `nextPageToken`).
Numeric service ids and the available search content/sort filter ids come from
`list_services`.

## n8n node

`n8n-node/` is an [n8n](https://n8n.io) community node package
(`n8n-nodes-pipepipe`) that drives the MCP server above over its Streamable HTTP
transport, exposing search and stream/channel/playlist/comment/kiosk extraction as
n8n operations with dropdowns and pagination. It is a standalone npm package and is
not part of the Gradle build; see [`n8n-node/README.md`](n8n-node/README.md).
