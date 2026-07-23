package org.schabi.newpipe.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

class CursorTest {

    private static McpJsonMapper mapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class)
                .findFirst()
                .map(McpJsonMapperSupplier::get)
                .orElseThrow();
    }

    @Test
    void pageRoundTripThroughContinuationToken() throws Exception {
        final Cursor cursor = new Cursor(mapper());

        final Page page = new Page("https://example.com/next", "page-2",
                List.of("a", "b"), Map.of("session", "token"),
                "body-bytes".getBytes(StandardCharsets.UTF_8));

        final String token = cursor.urlListNext("playlist", 0, "https://example.com/list", page);
        assertNotNull(token);

        final Map<String, Object> decoded = cursor.decode(token);
        assertEquals("playlist", decoded.get("kind"));
        assertEquals("https://example.com/list", decoded.get("url"));

        final Page restored = Cursor.pageFrom(decoded.get("page"));
        assertEquals(page.getUrl(), restored.getUrl());
        assertEquals(page.getId(), restored.getId());
        assertEquals(page.getIds(), restored.getIds());
        assertEquals(page.getCookies(), restored.getCookies());
        assertEquals("body-bytes", new String(restored.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void tabHandlerRoundTripPreservesFilterItems() throws Exception {
        final Cursor cursor = new Cursor(mapper());

        final FilterItem tabFilter =
                new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, ChannelTabs.VIDEOS);
        final ListLinkHandler handler = new ListLinkHandler(
                "https://example.com/c/1", "https://example.com/c/1/videos", "1",
                List.of(tabFilter), null);

        final String token = cursor.tabNavigation(5, handler);
        final Map<String, Object> decoded = cursor.decode(token);
        assertEquals("tab", decoded.get("kind"));

        final ListLinkHandler restored = Cursor.handlerFrom(decoded.get("tab"));
        assertEquals("https://example.com/c/1", restored.getOriginalUrl());
        assertEquals("https://example.com/c/1/videos", restored.getUrl());
        assertEquals("1", restored.getId());
        assertEquals(1, restored.getContentFilters().size());

        final FilterItem restoredItem = restored.getContentFilters().get(0);
        assertEquals(Filter.ITEM_IDENTIFIER_UNKNOWN, restoredItem.getIdentifier());
        assertEquals(ChannelTabs.VIDEOS, restoredItem.getName());
        // The name must be interned so tab extractors comparing it with '==' against the
        // ChannelTabs.* constants still match after the token round-trip.
        assertSame(ChannelTabs.VIDEOS, restoredItem.getName());
    }
}
