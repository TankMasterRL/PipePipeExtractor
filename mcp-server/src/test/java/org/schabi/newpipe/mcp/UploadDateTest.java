package org.schabi.newpipe.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The parsed upload date the serializer emits beside {@code textualUploadDate}.
 *
 * <p>It exists because the textual form follows the extraction language — for YouTube that is
 * Zulu unless a caller asks otherwise — while anything sorting or filtering on a date needs one
 * that does not move with the language.</p>
 */
class UploadDateTest {

    private static McpJsonMapper mapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class)
                .findFirst()
                .map(McpJsonMapperSupplier::get)
                .orElseThrow();
    }

    /** Serializes one item through the same path a paginated tool result takes. */
    private static Map<String, Object> serialize(final InfoItem item) {
        final McpJsonMapper mapper = mapper();
        final Map<String, Object> page = new Serializer(new Cursor(mapper)).itemsPage(
                new ListExtractor.InfoItemsPage<>(List.of(item), null, List.of()), null);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items.get(0);
    }

    private static StreamInfoItem streamItem(final String textual, final DateWrapper date) {
        final StreamInfoItem item = new StreamInfoItem(
                0, "https://example.com/watch?v=1", "a stream", StreamType.VIDEO_STREAM);
        item.setTextualUploadDate(textual);
        item.setUploadDate(date);
        return item;
    }

    private static OffsetDateTime utc(final int hour, final int minute) {
        return OffsetDateTime.of(2024, 3, 15, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void aParsedDateIsEmittedBesideTheTextualOne() {
        final Map<String, Object> json = serialize(
                streamItem("izinyanga ezingu-3 ezedlule", new DateWrapper(utc(10, 30), true)));

        // The textual form is untouched: it is still what the service actually said.
        assertEquals("izinyanga ezingu-3 ezedlule", json.get("textualUploadDate"));
        // The parsed one is language-independent and machine-comparable.
        assertEquals("2024-03-15T10:30Z", json.get("uploadDate"));
        assertEquals(Boolean.TRUE, json.get("uploadDateApproximate"));
    }

    @Test
    void anExactDateIsNotMarkedApproximate() {
        final Map<String, Object> json =
                serialize(streamItem("15 Mar 2024", new DateWrapper(utc(10, 30))));

        assertEquals(Boolean.FALSE, json.get("uploadDateApproximate"));
    }

    @Test
    void aNonUtcDateIsNormalizedRatherThanShiftedInWallTime() {
        // DateWrapper stores UTC, so the rendering must be the same instant, not the same clock
        // reading: 12:30+02:00 is 10:30Z.
        final OffsetDateTime when =
                OffsetDateTime.of(2024, 3, 15, 12, 30, 0, 0, ZoneOffset.ofHours(2));
        final Map<String, Object> json =
                serialize(streamItem("15 Mar 2024", new DateWrapper(when)));

        assertEquals("2024-03-15T10:30Z", json.get("uploadDate"));
    }

    @Test
    void bothKeysAreAbsentWhenNoDateCouldBeParsed() {
        final Map<String, Object> json = serialize(streamItem("some time ago", null));

        // Json.obj drops nulls, so an unparseable date leaves no misleading key behind rather
        // than a null or an epoch.
        assertFalse(json.containsKey("uploadDate"));
        assertFalse(json.containsKey("uploadDateApproximate"));
        assertEquals("some time ago", json.get("textualUploadDate"));
    }

    @Test
    void commentsCarryTheParsedDateToo() {
        final CommentsInfoItem comment =
                new CommentsInfoItem(0, "https://example.com/watch?v=1", "a comment");
        comment.setTextualUploadDate("izolo");
        comment.setUploadDate(new DateWrapper(utc(3, 4), true));

        final Map<String, Object> json = serialize(comment);

        assertEquals("izolo", json.get("textualUploadDate"));
        assertEquals("2024-03-15T03:04Z", json.get("uploadDate"));
        assertEquals(Boolean.TRUE, json.get("uploadDateApproximate"));
    }

    @Test
    void theParsedDateSerializesAsAPlainString() throws Exception {
        final McpJsonMapper mapper = mapper();
        final Map<String, Object> json =
                serialize(streamItem("3 months ago", new DateWrapper(utc(10, 30), true)));

        // A string rather than a Jackson-rendered temporal object, so a client needs no date
        // module configured to read it.
        final String rendered = mapper.writeValueAsString(json);
        assertTrue(rendered.contains("\"uploadDate\":\"2024-03-15T10:30Z\""), rendered);
    }
}
