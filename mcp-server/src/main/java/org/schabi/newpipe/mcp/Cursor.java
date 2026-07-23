package org.schabi.newpipe.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes opaque, self-contained continuation tokens used by the paginating tools.
 *
 * <p>A token is the URL-safe Base64 of a small JSON object describing how to fetch the next page
 * (the list {@code kind}, its identifying context, and the extractor's {@link Page}), so a client
 * only ever passes the token back verbatim without needing to understand it.</p>
 */
final class Cursor {

    private final McpJsonMapper jsonMapper;

    Cursor(final McpJsonMapper mapper) {
        this.jsonMapper = mapper;
    }

    String tabNavigation(final int serviceId, final ListLinkHandler handler) throws IOException {
        return encode(Json.obj("kind", "tab", "serviceId", serviceId, "tab", handlerMap(handler)));
    }

    String searchNext(final int serviceId,
                      final String query,
                      final List<Integer> contentFilterIds,
                      final List<Integer> sortFilterIds,
                      final Page page) throws IOException {
        return encode(Json.obj("kind", "search", "serviceId", serviceId, "query", query,
                "contentFilterIds", contentFilterIds, "sortFilterIds", sortFilterIds,
                "page", pageMap(page)));
    }

    String channelTabNext(final int serviceId, final ListLinkHandler handler, final Page page)
            throws IOException {
        return encode(Json.obj("kind", "channelTab", "serviceId", serviceId,
                "tab", handlerMap(handler), "page", pageMap(page)));
    }

    String urlListNext(final String kind, final int serviceId, final String url, final Page page)
            throws IOException {
        return encode(Json.obj("kind", kind, "serviceId", serviceId, "url", url,
                "page", pageMap(page)));
    }

    Map<String, Object> decode(final String token) throws IOException {
        final byte[] json = Base64.getUrlDecoder().decode(token);
        @SuppressWarnings("unchecked")
        final Map<String, Object> map =
                jsonMapper.readValue(new String(json, StandardCharsets.UTF_8), Map.class);
        return map;
    }

    static ListLinkHandler handlerFrom(final Object tab) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> map = (Map<String, Object>) tab;
        final List<FilterItem> contentFilters = filterItemsFrom(map.get("contentFilters"));
        return new ListLinkHandler(
                (String) map.get("originalUrl"),
                (String) map.get("url"),
                (String) map.get("id"),
                contentFilters == null ? new ArrayList<>() : contentFilters,
                filterItemsFrom(map.get("sortFilter")));
    }

    static Page pageFrom(final Object page) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> map = (Map<String, Object>) page;
        final Object body = map.get("body");
        return new Page(
                (String) map.get("url"),
                (String) map.get("id"),
                stringList(map.get("ids")),
                stringMap(map.get("cookies")),
                body == null ? null : Base64.getDecoder().decode((String) body));
    }

    private String encode(final Map<String, Object> map) throws IOException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                jsonMapper.writeValueAsString(map).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> handlerMap(final ListLinkHandler handler) {
        return Json.obj(
                "originalUrl", handler.getOriginalUrl(),
                "url", handler.getUrl(),
                "id", handler.getId(),
                "contentFilters", filterItemsMap(handler.getContentFilters()),
                "sortFilter", filterItemsMap(handler.getSortFilter()));
    }

    private static List<Map<String, Object>> filterItemsMap(final List<FilterItem> items) {
        if (items == null) {
            return null;
        }
        final List<Map<String, Object>> result = new ArrayList<>();
        for (final FilterItem item : items) {
            result.add(Json.obj("identifier", item.getIdentifier(), "name", item.getName()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<FilterItem> filterItemsFrom(final Object value) {
        if (value == null) {
            return null;
        }
        final List<FilterItem> result = new ArrayList<>();
        for (final Object element : (List<Object>) value) {
            final Map<String, Object> item = (Map<String, Object>) element;
            final int identifier = ((Number) item.get("identifier")).intValue();
            final Object nameValue = item.get("name");
            // Intern the name so extractors that compare a tab's filter name by reference
            // against interned ChannelTabs.* constants (with ==) still match after a round-trip.
            final String name = nameValue == null ? null : nameValue.toString().intern();
            result.add(new FilterItem(identifier, name));
        }
        return result;
    }

    private static Map<String, Object> pageMap(final Page page) {
        return Json.obj(
                "url", page.getUrl(),
                "id", page.getId(),
                "ids", page.getIds(),
                "cookies", page.getCookies(),
                "body", page.getBody() == null
                        ? null : Base64.getEncoder().encodeToString(page.getBody()));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(final Object value) {
        return value == null ? null : new ArrayList<>((List<String>) value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(final Object value) {
        return value == null ? null : new LinkedHashMap<>((Map<String, String>) value);
    }
}
