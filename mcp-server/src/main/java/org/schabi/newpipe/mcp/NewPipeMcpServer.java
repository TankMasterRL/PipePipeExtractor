package org.schabi.newpipe.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.schabi.newpipe.extractor.NewPipe;

import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point of the PipePipe Extractor MCP server.
 *
 * <p>It initializes the extractor with an {@link OkHttpDownloader}, builds the {@link NewPipeTools}
 * tool set, and serves it over the transport chosen on the command line: {@code stdio} (default),
 * {@code http} (Streamable HTTP) or {@code sse} (HTTP+SSE), the latter two hosted in an embedded
 * Jetty container.</p>
 *
 * <p>Usage: {@code [--transport stdio|http|sse] [--port PORT] [--language LANG]
 * [--country COUNTRY]}. {@code --language}/{@code --country} set what every tool extracts in
 * unless the call names a language of its own; see {@link Localizations}.</p>
 */
public final class NewPipeMcpServer {

    private static final String SERVER_NAME = "pipepipe-extractor";
    private static final String SERVER_VERSION = "0.1.0";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_PORT = 3000;

    private NewPipeMcpServer() {
    }

    public static void main(final String[] args) throws Exception {
        final Options options = Options.parse(args);
        NewPipe.init(new OkHttpDownloader(), options.requested.localizationOrDefault(),
                options.requested.contentCountryOrDefault());

        final McpJsonMapper mapper = defaultJsonMapper();
        // The same preference is handed to the tools, which force it per extractor. Setting it on
        // NewPipe alone is not enough: a service may override getLocalization() and ignore the
        // preference entirely, and YouTube does. Each tool call may still name its own language.
        final List<McpServerFeatures.SyncToolSpecification> tools =
                new NewPipeTools(mapper, options.requested).specifications();
        final McpSchema.ServerCapabilities capabilities =
                McpSchema.ServerCapabilities.builder().tools(false).build();

        if ("stdio".equals(options.transport)) {
            runStdio(mapper, capabilities, tools);
        } else if ("http".equals(options.transport)) {
            runHttp(mapper, capabilities, tools, options.port);
        } else if ("sse".equals(options.transport)) {
            runSse(mapper, capabilities, tools, options.port);
        } else {
            System.err.println("Unknown transport: " + options.transport
                    + " (expected stdio, http or sse)");
            System.exit(1);
        }
    }

    /**
     * Looks up the Jackson-backed {@link McpJsonMapper} the SDK registers via
     * {@link ServiceLoader}, since {@code McpJsonMapper} itself exposes no default-instance
     * factory (the {@code mcp} bundle pulls in {@code mcp-json-jackson3}, which registers
     * {@code JacksonMcpJsonMapperSupplier} as a {@link McpJsonMapperSupplier} service).
     *
     * @return the discovered default {@link McpJsonMapper}
     */
    private static McpJsonMapper defaultJsonMapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class)
                .findFirst()
                .map(McpJsonMapperSupplier::get)
                .orElseThrow(() -> new IllegalStateException(
                        "No McpJsonMapperSupplier found on the classpath"));
    }

    private static void runStdio(final McpJsonMapper mapper,
                                 final McpSchema.ServerCapabilities capabilities,
                                 final List<McpServerFeatures.SyncToolSpecification> tools)
            throws Exception {
        McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(capabilities)
                .tools(tools)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
        // Keep the process alive; the stdio transport serves requests on its own threads.
        new CountDownLatch(1).await();
    }

    private static void runHttp(final McpJsonMapper mapper,
                                final McpSchema.ServerCapabilities capabilities,
                                final List<McpServerFeatures.SyncToolSpecification> tools,
                                final int port) throws Exception {
        final HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint("/mcp")
                        .build();
        McpServer.sync(provider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(capabilities)
                .tools(tools)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
        System.err.println("PipePipe Extractor MCP server (Streamable HTTP) listening on "
                + "http://localhost:" + port + "/mcp");
        serve(provider, port);
    }

    private static void runSse(final McpJsonMapper mapper,
                               final McpSchema.ServerCapabilities capabilities,
                               final List<McpServerFeatures.SyncToolSpecification> tools,
                               final int port) throws Exception {
        final HttpServletSseServerTransportProvider provider =
                HttpServletSseServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .sseEndpoint("/sse")
                        .messageEndpoint("/message")
                        .build();
        McpServer.sync(provider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(capabilities)
                .tools(tools)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
        System.err.println("PipePipe Extractor MCP server (HTTP+SSE) listening on "
                + "http://localhost:" + port + "/sse");
        serve(provider, port);
    }

    private static void serve(final HttpServlet servlet, final int port) throws Exception {
        final Server jetty = new Server(port);
        final ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(servlet), "/*");
        jetty.setHandler(context);
        jetty.start();
        jetty.join();
    }

    private static final class Options {

        private String transport = "stdio";
        private int port = DEFAULT_PORT;
        /** What the command line asked to extract in, or {@link Localizations#NONE}. */
        private Localizations requested = Localizations.NONE;

        private static Options parse(final String[] args) {
            final Options options = new Options();
            String language = null;
            String country = null;
            for (int i = 0; i < args.length; i++) {
                final String arg = args[i];
                switch (arg) {
                    case "--transport":
                        options.transport = requireValue(args, ++i, arg);
                        break;
                    case "--port":
                        options.port = Integer.parseInt(requireValue(args, ++i, arg));
                        break;
                    case "--language":
                        language = requireValue(args, ++i, arg);
                        break;
                    case "--country":
                        country = requireValue(args, ++i, arg);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            // --country is honoured on its own, not only alongside --language: the two select
            // different things (the "hl" and "gl" an extractor sends), and a caller asking for one
            // has no reason to have the other silently ignored.
            options.requested = Localizations.of(language, country);
            return options;
        }

        private static String requireValue(final String[] args, final int index,
                                           final String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }
    }
}
