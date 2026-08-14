package org.schabi.newpipe.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

class LocalizationsTest {

    /**
     * {@code Extractor}'s constructor requires a downloader, so one has to be installed even
     * though no test here fetches anything — constructing an extractor touches no network.
     */
    @BeforeAll
    static void initExtractor() {
        NewPipe.init(new OkHttpDownloader());
    }

    private static McpJsonMapper mapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class)
                .findFirst()
                .map(McpJsonMapperSupplier::get)
                .orElseThrow();
    }

    @Test
    void blankAndMissingValuesMeanNoPreference() {
        assertSame(Localizations.NONE, Localizations.of(null, null));
        assertSame(Localizations.NONE, Localizations.of("  ", "\t"));
        assertTrue(Localizations.NONE.isEmpty());
        assertNull(Localizations.NONE.language());
        assertNull(Localizations.NONE.country());
    }

    @Test
    void languageMayCarryItsOwnRegion() {
        final Localizations both = Localizations.of("en-GB", null);
        assertFalse(both.isEmpty());
        assertEquals("en-GB", both.language());
        // A region inside the language code also supplies the content country.
        assertEquals("GB", both.country());
    }

    @Test
    void underscoreFormIsAccepted() {
        assertEquals("pt-BR", Localizations.of("pt_BR", null).language());
    }

    @Test
    void separateCountrySuppliesTheRegionAndWinsForContentCountry() {
        final Localizations combined = Localizations.of("en", "SE");
        assertEquals("en-SE", combined.language());
        assertEquals("SE", combined.country());

        // An explicit country beats the one inside the language code.
        assertEquals("US", Localizations.of("en-GB", "US").country());
    }

    @Test
    void countryAloneLeavesTheLanguageUntouched() {
        final Localizations countryOnly = Localizations.of(null, "SE");
        assertFalse(countryOnly.isEmpty());
        assertNull(countryOnly.language());
        assertEquals("SE", countryOnly.country());
        // Nothing was asked of the language, so the service keeps its own.
        assertSame(Localization.DEFAULT, countryOnly.localizationOrDefault());
    }

    @Test
    void defaultsFillInForTheHalvesThatWereNotRequested() {
        assertSame(Localization.DEFAULT, Localizations.NONE.localizationOrDefault());
        assertSame(ContentCountry.DEFAULT, Localizations.NONE.contentCountryOrDefault());

        final Localizations english = Localizations.of("en-GB", null);
        assertEquals("en-GB", english.localizationOrDefault().getLocalizationCode());
        assertEquals("GB", english.contentCountryOrDefault().getCountryCode());
    }

    /**
     * The reason this class exists: YouTube's service overrides {@code getLocalization()} to
     * {@code zu} and ignores the process-wide preference, so only a forced localization reaches
     * the request.
     */
    @Test
    void forcingBeatsTheServicesOwnLocalization() throws Exception {
        // Constructing an extractor touches no network; only fetchPage() would.
        final StreamExtractor extractor = ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("zu", extractor.getExtractorLocalization().getLocalizationCode(),
                "YouTube is expected to default to Zulu; the override below is what this is for");

        Localizations.of("en-GB", null).applyTo(extractor);
        assertEquals("en-GB", extractor.getExtractorLocalization().getLocalizationCode());
        assertEquals("GB", extractor.getExtractorContentCountry().getCountryCode());
    }

    @Test
    void aRequestedLanguageSurvivesAContinuationToken() throws Exception {
        final Cursor cursor = new Cursor(mapper(), Localizations.of("en-GB", "SE"));
        final String token = cursor.urlListNext("playlist", 0, "https://example.com/list",
                new Page("https://example.com/next"));

        final Map<String, Object> decoded = cursor.decode(token);
        assertEquals("en-GB", decoded.get("language"));
        assertEquals("SE", decoded.get("country"));

        final Localizations restored = Cursor.localizationFrom(decoded);
        assertEquals("en-GB", restored.language());
        assertEquals("SE", restored.country());
    }

    @Test
    void aTokenWithoutALanguageDecodesToNoPreference() throws Exception {
        final Cursor cursor = new Cursor(mapper());
        final String token = cursor.urlListNext("playlist", 0, "https://example.com/list",
                new Page("https://example.com/next"));

        final Map<String, Object> decoded = cursor.decode(token);
        assertFalse(decoded.containsKey("language"));
        assertSame(Localizations.NONE, Cursor.localizationFrom(decoded));
        assertSame(Localizations.NONE, Cursor.localizationFrom(Map.of()));
    }

    @Test
    void everyExtractorBackedToolAcceptsALanguageArgument() throws Exception {
        final List<String> localizable = List.of("get_suggestions", "search", "get_stream",
                "get_channel", "get_channel_tab", "get_playlist", "get_comments", "get_kiosk",
                "get_feed", "get_more");

        final McpJsonMapper mapper = mapper();
        final Map<String, String> schemas = new LinkedHashMap<>();
        for (final McpServerFeatures.SyncToolSpecification spec
                : new NewPipeTools(mapper).specifications()) {
            schemas.put(spec.tool().name(), mapper.writeValueAsString(spec.tool().inputSchema()));
        }

        for (final String name : localizable) {
            final String schema = schemas.get(name);
            assertNotNull(schema, name + " should be exposed as a tool");
            assertTrue(schema.contains("\"language\""),
                    name + " should accept a language argument");
            assertTrue(schema.contains("\"country\""),
                    name + " should accept a country argument");
        }
        // list_services reaches no extractor, so it takes neither.
        assertFalse(schemas.get("list_services").contains("\"language\""));
    }
}
