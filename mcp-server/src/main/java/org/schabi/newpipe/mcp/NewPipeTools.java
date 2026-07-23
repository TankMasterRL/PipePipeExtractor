package org.schabi.newpipe.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.feed.FeedInfo;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Defines the MCP tools exposed by the server, each mapping to a public PipePipe Extractor call.
 * Every tool returns its result as a JSON text content block (plus {@code structuredContent}) and
 * reports failures as an error result rather than throwing out of the server loop.
 */
final class NewPipeTools {

    private final McpJsonMapper jsonMapper;
    private final Cursor cursor;
    private final Serializer serializer;

    NewPipeTools(final McpJsonMapper mapper) {
        this.jsonMapper = mapper;
        this.cursor = new Cursor(mapper);
        this.serializer = new Serializer(cursor);
    }

    @FunctionalInterface
    private interface ToolFn {
        Object apply(Map<String, Object> arguments) throws Exception;
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        final List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        specs.add(spec("list_services",
                "List the streaming services supported by PipePipe Extractor, with their numeric "
                        + "id, name, media capabilities, available search filters and kiosk ids.",
                schema(Json.obj(), List.of()),
                this::listServices));

        specs.add(spec("get_suggestions",
                "Get search autocomplete suggestions for a query on a given service.",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "query", stringProp("Partial query to autocomplete")),
                        List.of("serviceId", "query")),
                this::getSuggestions));

        specs.add(spec("search",
                "Search a service. Returns the first page of results; use get_more with the "
                        + "returned nextPageToken for further pages.",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "query", stringProp("The search query"),
                        "contentFilters", intArrayProp(
                                "Optional content filter ids (see searchContentFilters)"),
                        "sortFilters", intArrayProp(
                                "Optional sort filter ids (see searchSortFilters)")),
                        List.of("serviceId", "query")),
                this::search));

        specs.add(spec("get_stream",
                "Get details of a single stream (video/track) by its URL, including metadata and "
                        + "playable audio/video stream URLs (which may be time-limited).",
                schema(Json.obj("url", stringProp("The stream URL")), List.of("url")),
                this::getStream));

        specs.add(spec("get_channel",
                "Get channel details by URL, including the available tabs. Each tab carries a "
                        + "token to pass to get_channel_tab.",
                schema(Json.obj("url", stringProp("The channel URL")), List.of("url")),
                this::getChannel));

        specs.add(spec("get_channel_tab",
                "Get the first page of items of a channel tab, using a tab token from get_channel.",
                schema(Json.obj("tabToken", stringProp("A tab token returned by get_channel")),
                        List.of("tabToken")),
                this::getChannelTab));

        specs.add(spec("get_playlist",
                "Get a playlist by URL: its metadata and the first page of its streams.",
                schema(Json.obj("url", stringProp("The playlist URL")), List.of("url")),
                this::getPlaylist));

        specs.add(spec("get_comments",
                "Get the first page of comments for a stream URL. Reports if comments are "
                        + "unsupported or disabled.",
                schema(Json.obj("url", stringProp("The stream URL whose comments to fetch")),
                        List.of("url")),
                this::getComments));

        specs.add(spec("get_kiosk",
                "Get a kiosk (e.g. trending/charts) for a service. Omit kioskId for the default "
                        + "kiosk; available kiosk ids are listed by list_services.",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "kioskId", stringProp("Optional kiosk id (see kiosks in list_services)")),
                        List.of("serviceId")),
                this::getKiosk));

        specs.add(spec("get_feed",
                "Get a service's lightweight channel feed by URL (only where the service supports "
                        + "a dedicated feed).",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "url", stringProp("The channel URL")),
                        List.of("serviceId", "url")),
                this::getFeed));

        specs.add(spec("get_more",
                "Fetch the next page of any paginated result, using a nextPageToken returned by "
                        + "search, get_channel_tab, get_playlist, get_comments or get_kiosk.",
                schema(Json.obj("pageToken", stringProp("A nextPageToken from a previous result")),
                        List.of("pageToken")),
                this::getMore));

        return specs;
    }

    private Object listServices(final Map<String, Object> arguments) {
        final List<Object> services = new ArrayList<>();
        for (final StreamingService service : NewPipe.getServices()) {
            services.add(serializer.service(service));
        }
        return Json.obj("services", services);
    }

    private Object getSuggestions(final Map<String, Object> arguments) throws Exception {
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        final SuggestionExtractor extractor = service.getSuggestionExtractor();
        if (extractor == null) {
            return Json.obj("suggestions", new ArrayList<>());
        }
        return Json.obj("suggestions", extractor.suggestionList(reqStr(arguments, "query")));
    }

    private Object search(final Map<String, Object> arguments) throws Exception {
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        final SearchQueryHandlerFactory factory = service.getSearchQHFactory();
        final List<FilterItem> contentFilters =
                resolveFilterIds(factory, asIntList(arguments.get("contentFilters")));
        final List<FilterItem> sortFilters =
                resolveFilterIds(factory, asIntList(arguments.get("sortFilters")));
        final SearchQueryHandler handler = factory.fromQuery(reqStr(arguments, "query"),
                contentFilters, sortFilters.isEmpty() ? null : sortFilters);
        return serializer.searchInfo(SearchInfo.getInfo(service, handler));
    }

    private Object getStream(final Map<String, Object> arguments) throws Exception {
        return serializer.streamInfo(StreamInfo.getInfo(reqStr(arguments, "url")));
    }

    private Object getChannel(final Map<String, Object> arguments) throws Exception {
        return serializer.channelInfo(ChannelInfo.getInfo(reqStr(arguments, "url")));
    }

    private Object getChannelTab(final Map<String, Object> arguments) throws Exception {
        final Map<String, Object> tab = cursor.decode(reqStr(arguments, "tabToken"));
        final StreamingService service = NewPipe.getService(intField(tab, "serviceId"));
        final ListLinkHandler handler = Cursor.handlerFrom(tab.get("tab"));
        return serializer.channelTabInfo(ChannelTabInfo.getInfo(service, handler));
    }

    private Object getPlaylist(final Map<String, Object> arguments) throws Exception {
        return serializer.playlistInfo(PlaylistInfo.getInfo(reqStr(arguments, "url")));
    }

    private Object getComments(final Map<String, Object> arguments) throws Exception {
        final CommentsInfo info = CommentsInfo.getInfo(reqStr(arguments, "url"));
        if (info == null) {
            return Json.obj("commentsSupported", false,
                    "message", "This service does not support comments extraction.");
        }
        return serializer.commentsInfo(info);
    }

    private Object getKiosk(final Map<String, Object> arguments) throws Exception {
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        final String kioskId = str(arguments, "kioskId");
        final KioskExtractor extractor = kioskId == null || kioskId.isEmpty()
                ? service.getKioskList().getDefaultKioskExtractor()
                : service.getKioskList().getExtractorById(kioskId, null);
        if (extractor == null) {
            throw new IllegalArgumentException("No kiosk is available for this service");
        }
        extractor.fetchPage();
        return serializer.kioskInfo(KioskInfo.getInfo(extractor));
    }

    private Object getFeed(final Map<String, Object> arguments) throws Exception {
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        return serializer.feedInfo(FeedInfo.getInfo(service, reqStr(arguments, "url")));
    }

    private Object getMore(final Map<String, Object> arguments) throws Exception {
        final Map<String, Object> cont = cursor.decode(reqStr(arguments, "pageToken"));
        final String kind = (String) cont.get("kind");
        final int serviceId = intField(cont, "serviceId");
        final StreamingService service = NewPipe.getService(serviceId);
        final Page page = Cursor.pageFrom(cont.get("page"));

        final ListExtractor.InfoItemsPage<? extends InfoItem> result;
        final String nextToken;
        if ("search".equals(kind)) {
            final String query = (String) cont.get("query");
            final List<Integer> contentFilterIds = asIntList(cont.get("contentFilterIds"));
            final List<Integer> sortFilterIds = asIntList(cont.get("sortFilterIds"));
            final SearchQueryHandlerFactory factory = service.getSearchQHFactory();
            final List<FilterItem> contentFilters = resolveFilterIds(factory, contentFilterIds);
            final List<FilterItem> sortFilters = resolveFilterIds(factory, sortFilterIds);
            final SearchQueryHandler handler = factory.fromQuery(query, contentFilters,
                    sortFilters.isEmpty() ? null : sortFilters);
            final ListExtractor.InfoItemsPage<InfoItem> next =
                    SearchInfo.getMoreItems(service, handler, page);
            result = next;
            nextToken = next.hasNextPage() ? cursor.searchNext(serviceId, query,
                    contentFilterIds, sortFilterIds, next.getNextPage()) : null;
        } else if ("channelTab".equals(kind)) {
            final ListLinkHandler handler = Cursor.handlerFrom(cont.get("tab"));
            final ListExtractor.InfoItemsPage<InfoItem> next =
                    ChannelTabInfo.getMoreItems(service, handler, page);
            result = next;
            nextToken = next.hasNextPage()
                    ? cursor.channelTabNext(serviceId, handler, next.getNextPage()) : null;
        } else if ("playlist".equals(kind)) {
            final String url = (String) cont.get("url");
            final ListExtractor.InfoItemsPage<StreamInfoItem> next =
                    PlaylistInfo.getMoreItems(service, url, page);
            result = next;
            nextToken = next.hasNextPage()
                    ? cursor.urlListNext("playlist", serviceId, url, next.getNextPage()) : null;
        } else if ("comments".equals(kind)) {
            final String url = (String) cont.get("url");
            final ListExtractor.InfoItemsPage<CommentsInfoItem> next =
                    CommentsInfo.getMoreItems(service, url, page);
            result = next;
            nextToken = next.hasNextPage()
                    ? cursor.urlListNext("comments", serviceId, url, next.getNextPage()) : null;
        } else if ("kiosk".equals(kind)) {
            final String url = (String) cont.get("url");
            final ListExtractor.InfoItemsPage<StreamInfoItem> next =
                    KioskInfo.getMoreItems(service, url, page);
            result = next;
            nextToken = next.hasNextPage()
                    ? cursor.urlListNext("kiosk", serviceId, url, next.getNextPage()) : null;
        } else {
            throw new IllegalArgumentException("Unknown or non-paginable page token");
        }
        return serializer.itemsPage(result, nextToken);
    }

    private static List<FilterItem> resolveFilterIds(final SearchQueryHandlerFactory factory,
                                                     final List<Integer> ids) {
        final List<FilterItem> result = new ArrayList<>();
        if (ids != null) {
            for (final Integer id : ids) {
                final FilterItem item = factory.getFilterItem(id);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private McpServerFeatures.SyncToolSpecification spec(final String name,
                                                         final String description,
                                                         final Map<String, Object> inputSchema,
                                                         final ToolFn function) {
        final McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        final Map<String, Object> arguments = request.arguments() == null
                                ? Map.of() : request.arguments();
                        return ok(function.apply(arguments));
                    } catch (final Exception e) {
                        return error(e);
                    }
                })
                .build();
    }

    private McpSchema.CallToolResult ok(final Object result) {
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(jsonMapper.writeValueAsString(result))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (final IOException e) {
            return error(e);
        }
    }

    private static McpSchema.CallToolResult error(final Throwable throwable) {
        final String message = throwable.getMessage() == null
                ? throwable.toString() : throwable.getMessage();
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + message)
                .isError(true)
                .build();
    }

    private static Map<String, Object> schema(final Map<String, Object> properties,
                                              final List<String> required) {
        final Map<String, Object> map = Json.obj("type", "object", "properties", properties);
        if (required != null && !required.isEmpty()) {
            map.put("required", required);
        }
        return map;
    }

    private static Map<String, Object> stringProp(final String description) {
        return Json.obj("type", "string", "description", description);
    }

    private static Map<String, Object> intProp(final String description) {
        return Json.obj("type", "integer", "description", description);
    }

    private static Map<String, Object> intArrayProp(final String description) {
        return Json.obj("type", "array", "items", Json.obj("type", "integer"),
                "description", description);
    }

    private static String str(final Map<String, Object> arguments, final String key) {
        final Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static String reqStr(final Map<String, Object> arguments, final String key) {
        final String value = str(arguments, key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    private static int reqInt(final Map<String, Object> arguments, final String key) {
        final Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value instanceof Number
                ? ((Number) value).intValue() : Integer.parseInt(value.toString());
    }

    private static int intField(final Map<String, Object> map, final String key) {
        final Object value = map.get(key);
        return value instanceof Number
                ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> asIntList(final Object value) {
        if (value == null) {
            return null;
        }
        final List<Integer> result = new ArrayList<>();
        if (value instanceof List) {
            for (final Object element : (List<Object>) value) {
                if (element instanceof Number) {
                    result.add(((Number) element).intValue());
                } else if (element != null) {
                    result.add(Integer.parseInt(element.toString()));
                }
            }
        } else if (value instanceof Number) {
            result.add(((Number) value).intValue());
        } else {
            result.add(Integer.parseInt(value.toString()));
        }
        return result;
    }
}
