package org.schabi.newpipe.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.feed.FeedExtractor;
import org.schabi.newpipe.extractor.feed.FeedInfo;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
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
    private final Localizations defaultLocalization;

    NewPipeTools(final McpJsonMapper mapper) {
        this(mapper, Localizations.NONE);
    }

    /**
     * @param defaultLocalization what to extract in when a call names no language of its own —
     *                            the server's {@code --language}/{@code --country}, or
     *                            {@link Localizations#NONE} to leave every service on its own
     *                            default
     */
    NewPipeTools(final McpJsonMapper mapper, final Localizations defaultLocalization) {
        this.jsonMapper = mapper;
        this.cursor = new Cursor(mapper);
        this.serializer = new Serializer(cursor);
        this.defaultLocalization = defaultLocalization;
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

        specs.add(localizableSpec("get_suggestions",
                "Get search autocomplete suggestions for a query on a given service.",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "query", stringProp("Partial query to autocomplete")),
                        List.of("serviceId", "query")),
                this::getSuggestions));

        specs.add(localizableSpec("search",
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

        specs.add(localizableSpec("get_stream",
                "Get details of a single stream (video/track) by its URL, including metadata and "
                        + "playable audio/video stream URLs (which may be time-limited).",
                schema(Json.obj("url", stringProp("The stream URL")), List.of("url")),
                this::getStream));

        specs.add(localizableSpec("get_channel",
                "Get channel details by URL, including the available tabs. Each tab carries a "
                        + "token to pass to get_channel_tab.",
                schema(Json.obj("url", stringProp("The channel URL")), List.of("url")),
                this::getChannel));

        specs.add(localizableSpec("get_channel_tab",
                "Get the first page of items of a channel tab, using a tab token from get_channel.",
                schema(Json.obj("tabToken", stringProp("A tab token returned by get_channel")),
                        List.of("tabToken")),
                this::getChannelTab));

        specs.add(localizableSpec("get_playlist",
                "Get a playlist by URL: its metadata and the first page of its streams.",
                schema(Json.obj("url", stringProp("The playlist URL")), List.of("url")),
                this::getPlaylist));

        specs.add(localizableSpec("get_comments",
                "Get the first page of comments for a stream URL. Reports if comments are "
                        + "unsupported or disabled.",
                schema(Json.obj("url", stringProp("The stream URL whose comments to fetch")),
                        List.of("url")),
                this::getComments));

        specs.add(localizableSpec("get_kiosk",
                "Get a kiosk (e.g. trending/charts) for a service. Omit kioskId for the default "
                        + "kiosk; available kiosk ids are listed by list_services.",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "kioskId", stringProp("Optional kiosk id (see kiosks in list_services)")),
                        List.of("serviceId")),
                this::getKiosk));

        specs.add(localizableSpec("get_feed",
                "Get a service's lightweight channel feed by URL (only where the service supports "
                        + "a dedicated feed).",
                schema(Json.obj(
                        "serviceId", intProp("Numeric service id (see list_services)"),
                        "url", stringProp("The channel URL")),
                        List.of("serviceId", "url")),
                this::getFeed));

        specs.add(localizableSpec("get_more",
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
        localizationOf(arguments).applyTo(extractor);
        return Json.obj("suggestions", extractor.suggestionList(reqStr(arguments, "query")));
    }

    private Object search(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        final SearchQueryHandlerFactory factory = service.getSearchQHFactory();
        final List<FilterItem> contentFilters =
                contentFiltersOrDefault(factory, asIntList(arguments.get("contentFilters")));
        final List<FilterItem> sortFilters =
                resolveFilterIds(factory, asIntList(arguments.get("sortFilters")));
        final SearchQueryHandler handler = factory.fromQuery(reqStr(arguments, "query"),
                contentFilters, sortFilters.isEmpty() ? null : sortFilters);
        final SearchExtractor extractor = service.getSearchExtractor(handler);
        localization.applyTo(extractor);
        extractor.fetchPage();
        return serializerFor(localization).searchInfo(SearchInfo.getInfo(extractor));
    }

    private Object getStream(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final String url = reqStr(arguments, "url");
        final StreamExtractor extractor = NewPipe.getServiceByUrl(url).getStreamExtractor(url);
        localization.applyTo(extractor);
        // StreamInfo.getInfo(extractor) fetches the page itself, unlike its list counterparts.
        return serializerFor(localization).streamInfo(StreamInfo.getInfo(extractor));
    }

    private Object getChannel(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final String url = reqStr(arguments, "url");
        final ChannelExtractor extractor = NewPipe.getServiceByUrl(url).getChannelExtractor(url);
        localization.applyTo(extractor);
        extractor.fetchPage();
        return serializerFor(localization).channelInfo(ChannelInfo.getInfo(extractor));
    }

    private Object getChannelTab(final Map<String, Object> arguments) throws Exception {
        final Map<String, Object> tab = cursor.decode(reqStr(arguments, "tabToken"));
        final Localizations localization =
                localizationOf(arguments, Cursor.localizationFrom(tab));
        final StreamingService service = NewPipe.getService(intField(tab, "serviceId"));
        final ListLinkHandler handler = Cursor.handlerFrom(tab.get("tab"));
        final ChannelTabExtractor extractor = service.getChannelTabExtractor(handler);
        localization.applyTo(extractor);
        extractor.fetchPage();
        return serializerFor(localization).channelTabInfo(ChannelTabInfo.getInfo(extractor));
    }

    private Object getPlaylist(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final String url = reqStr(arguments, "url");
        final PlaylistExtractor extractor = NewPipe.getServiceByUrl(url).getPlaylistExtractor(url);
        localization.applyTo(extractor);
        extractor.fetchPage();
        return serializerFor(localization).playlistInfo(PlaylistInfo.getInfo(extractor));
    }

    private Object getComments(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final String url = reqStr(arguments, "url");
        final CommentsExtractor extractor = NewPipe.getServiceByUrl(url).getCommentsExtractor(url);
        if (extractor == null) {
            return Json.obj("commentsSupported", false,
                    "message", "This service does not support comments extraction.");
        }
        localization.applyTo(extractor);
        // CommentsInfo.getInfo(extractor) fetches the page itself.
        final CommentsInfo info = CommentsInfo.getInfo(extractor);
        return serializerFor(localization).commentsInfo(info);
    }

    private Object getKiosk(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        final String kioskId = str(arguments, "kioskId");
        final KioskExtractor extractor = kioskId == null || kioskId.isEmpty()
                ? service.getKioskList().getDefaultKioskExtractor()
                : service.getKioskList().getExtractorById(kioskId, null);
        if (extractor == null) {
            throw new IllegalArgumentException("No kiosk is available for this service");
        }
        localization.applyTo(extractor);
        extractor.fetchPage();
        return serializerFor(localization).kioskInfo(KioskInfo.getInfo(extractor));
    }

    private Object getFeed(final Map<String, Object> arguments) throws Exception {
        final Localizations localization = localizationOf(arguments);
        final StreamingService service = NewPipe.getService(reqInt(arguments, "serviceId"));
        final FeedExtractor extractor = service.getFeedExtractor(reqStr(arguments, "url"));
        if (extractor == null) {
            throw new IllegalArgumentException("Service \""
                    + service.getServiceInfo().getName() + "\" doesn't support FeedExtractor.");
        }
        localization.applyTo(extractor);
        // FeedInfo.getInfo(extractor) fetches the page itself.
        return serializerFor(localization).feedInfo(FeedInfo.getInfo(extractor));
    }

    private Object getMore(final Map<String, Object> arguments) throws Exception {
        final Map<String, Object> cont = cursor.decode(reqStr(arguments, "pageToken"));
        // The token carries the language its own page was fetched in, so a continuation stays in
        // that language without the client having to repeat it. An explicit argument still wins.
        final Localizations localization = localizationOf(arguments, Cursor.localizationFrom(cont));
        final Cursor pageCursor = new Cursor(jsonMapper, localization);
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
            final List<FilterItem> contentFilters =
                    contentFiltersOrDefault(factory, contentFilterIds);
            final List<FilterItem> sortFilters = resolveFilterIds(factory, sortFilterIds);
            final SearchQueryHandler handler = factory.fromQuery(query, contentFilters,
                    sortFilters.isEmpty() ? null : sortFilters);
            final SearchExtractor extractor = service.getSearchExtractor(handler);
            localization.applyTo(extractor);
            final ListExtractor.InfoItemsPage<InfoItem> next = extractor.getPage(page);
            result = next;
            nextToken = next.hasNextPage() ? pageCursor.searchNext(serviceId, query,
                    contentFilterIds, sortFilterIds, next.getNextPage()) : null;
        } else if ("channelTab".equals(kind)) {
            final ListLinkHandler handler = Cursor.handlerFrom(cont.get("tab"));
            final ChannelTabExtractor extractor = service.getChannelTabExtractor(handler);
            localization.applyTo(extractor);
            final ListExtractor.InfoItemsPage<InfoItem> next = extractor.getPage(page);
            result = next;
            nextToken = next.hasNextPage()
                    ? pageCursor.channelTabNext(serviceId, handler, next.getNextPage()) : null;
        } else if ("playlist".equals(kind)) {
            final String url = (String) cont.get("url");
            final PlaylistExtractor extractor = service.getPlaylistExtractor(url);
            localization.applyTo(extractor);
            final ListExtractor.InfoItemsPage<StreamInfoItem> next = extractor.getPage(page);
            result = next;
            nextToken = next.hasNextPage()
                    ? pageCursor.urlListNext("playlist", serviceId, url, next.getNextPage()) : null;
        } else if ("comments".equals(kind)) {
            final String url = (String) cont.get("url");
            final CommentsExtractor extractor = service.getCommentsExtractor(url);
            localization.applyTo(extractor);
            final ListExtractor.InfoItemsPage<CommentsInfoItem> next = extractor.getPage(page);
            result = next;
            nextToken = next.hasNextPage()
                    ? pageCursor.urlListNext("comments", serviceId, url, next.getNextPage()) : null;
        } else if ("kiosk".equals(kind)) {
            final String url = (String) cont.get("url");
            final KioskExtractor extractor = service.getKioskList().getExtractorByUrl(url, page);
            localization.applyTo(extractor);
            final ListExtractor.InfoItemsPage<StreamInfoItem> next = extractor.getPage(page);
            result = next;
            nextToken = next.hasNextPage()
                    ? pageCursor.urlListNext("kiosk", serviceId, url, next.getNextPage()) : null;
        } else {
            throw new IllegalArgumentException("Unknown or non-paginable page token");
        }
        return new Serializer(pageCursor).itemsPage(result, nextToken);
    }

    /** The language a call should run in: its own arguments, else the server's default. */
    private Localizations localizationOf(final Map<String, Object> arguments) {
        return localizationOf(arguments, defaultLocalization);
    }

    /**
     * The same, for a call that has a second source to fall back on before the server default —
     * the localization recorded in the page or tab token it was given.
     */
    private Localizations localizationOf(final Map<String, Object> arguments,
                                         final Localizations fallback) {
        final Localizations requested =
                Localizations.of(str(arguments, "language"), str(arguments, "country"));
        if (!requested.isEmpty()) {
            return requested;
        }
        return fallback.isEmpty() ? defaultLocalization : fallback;
    }

    /** A serializer whose page tokens carry {@code localization}, so continuations inherit it. */
    private Serializer serializerFor(final Localizations localization) {
        return new Serializer(new Cursor(jsonMapper, localization));
    }

    /**
     * Resolves the caller's content filter ids, falling back to the service's default content
     * filter when none of them resolve.
     *
     * <p>{@code contentFilters} is documented as optional, but for three of the seven services it
     * is not, and each fails differently: YouTube throws
     * {@code RuntimeException("we have a problem here")} out of
     * {@code YoutubeFilters.evaluateSelectedFilters}, NicoNico reads element 0 of the list
     * unguarded, and BiliBili builds a {@code search/type} URL with no {@code search_type}
     * parameter. Passing the default is also what a UI does: a search screen always has one
     * content filter selected, which is what {@code SearchFiltersBase.defaultContentFilterId} is
     * for.
     *
     * <p>The remaining four are unaffected: MediaCCC ignores content filters entirely, and
     * SoundCloud, Bandcamp and PeerTube all default to an "all" item that contributes nothing to
     * the query.
     */
    static List<FilterItem> contentFiltersOrDefault(
            final SearchQueryHandlerFactory factory, final List<Integer> ids) {
        final List<FilterItem> resolved = resolveFilterIds(factory, ids);
        if (!resolved.isEmpty()) {
            return resolved;
        }
        final FilterItem fallback = defaultContentFilter(factory);
        return fallback == null ? resolved : List.of(fallback);
    }

    /**
     * The content filter a service treats as its default, or null for a service that offers none.
     *
     * <p>{@code defaultContentFilterId} is protected in {@code SearchFiltersBase} with no
     * accessor, so it is read through the public filter list instead: every service registers its
     * default as the first item of the first group it adds, which is the item a UI would start on.
     */
    private static FilterItem defaultContentFilter(final SearchQueryHandlerFactory factory) {
        final Filter available = factory.getAvailableContentFilter();
        if (available == null || available.getFilterGroups() == null) {
            return null;
        }
        for (final FilterGroup group : available.getFilterGroups()) {
            if (group == null || group.filterItems == null) {
                continue;
            }
            for (final FilterItem item : group.filterItems) {
                if (item != null) {
                    return item;
                }
            }
        }
        return null;
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

    /**
     * A {@link #spec} for a tool backed by an extractor, adding the optional {@code language} and
     * {@code country} arguments every such tool honours.
     */
    @SuppressWarnings("unchecked")
    private McpServerFeatures.SyncToolSpecification localizableSpec(
            final String name,
            final String description,
            final Map<String, Object> inputSchema,
            final ToolFn function) {
        final Map<String, Object> properties =
                (Map<String, Object>) inputSchema.get("properties");
        properties.put("language", stringProp(
                "Optional language for the text this call returns, as an ISO 639-1 code with an "
                        + "optional region (\"en\", \"en-GB\"). Defaults to the server's "
                        + "--language. Note that YouTube otherwise extracts in Zulu, which is what "
                        + "keeps video titles untranslated but renders view counts, subscriber "
                        + "counts and upload dates in Zulu."));
        properties.put("country", stringProp(
                "Optional content country as an ISO 3166-1 alpha-2 code (\"GB\", \"SE\"), "
                        + "deciding which region's results a service returns. Defaults to the "
                        + "server's --country."));
        return spec(name, description, inputSchema, function);
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
