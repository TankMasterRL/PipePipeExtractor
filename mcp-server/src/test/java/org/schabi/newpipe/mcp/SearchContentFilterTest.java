package org.schabi.newpipe.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/**
 * The {@code search} tool documents {@code contentFilters} as optional, so a call that omits them
 * has to build a usable query for every service. Three of the seven do not tolerate an empty
 * content filter list, each in its own way, which is what
 * {@link NewPipeTools#contentFiltersOrDefault} exists to paper over.
 *
 * <p>Offline: only {@code SearchQueryHandlerFactory.getUrl} is exercised, which builds a URL
 * without downloading anything.
 */
class SearchContentFilterTest {

    private static final String QUERY = "lofi hip hop";

    private static String urlWithoutContentFilters(final StreamingService service)
            throws Exception {
        final SearchQueryHandlerFactory factory = service.getSearchQHFactory();
        return factory.fromQuery(QUERY, NewPipeTools.contentFiltersOrDefault(factory, List.of()),
                null).getUrl();
    }

    @Test
    void youTubeSearchWithoutContentFiltersBuildsAUrl() throws Exception {
        // Without the default, YoutubeFilters.evaluateSelectedFilters falls through every branch
        // and throws RuntimeException("we have a problem here").
        final String url = urlWithoutContentFilters(ServiceList.YouTube);
        assertTrue(url.startsWith("https://www.youtube.com/results?search_query="), url);
    }

    @Test
    void niconicoSearchWithoutContentFiltersBuildsAUrl() throws Exception {
        // Without the default, NiconicoSearchQueryHandlerFactory.getUrl reads
        // selectedContentFilter.get(0) on an empty list.
        final String url = urlWithoutContentFilters(ServiceList.NicoNico);
        assertFalse(url.isEmpty());
    }

    @Test
    void biliBiliSearchWithoutContentFiltersStillNamesASearchType() throws Exception {
        // BiliBili's endpoint is search/type, which needs the search_type the content filter
        // carries; without the default the parameter is simply absent from the URL.
        final String url = urlWithoutContentFilters(ServiceList.BiliBili);
        assertTrue(url.contains("search_type="), url);
    }

    @Test
    void everyServiceOfferingContentFiltersResolvesExactlyOneDefault() {
        for (final StreamingService service : ServiceList.all()) {
            final SearchQueryHandlerFactory factory = service.getSearchQHFactory();
            if (factory.getAvailableContentFilter() == null) {
                continue;
            }
            final List<FilterItem> filters = NewPipeTools.contentFiltersOrDefault(factory,
                    List.of());
            assertEquals(1, filters.size(), service.getServiceInfo().getName());
        }
    }

    @Test
    void anExplicitContentFilterIsNotReplacedByTheDefault() {
        final SearchQueryHandlerFactory factory = ServiceList.YouTube.getSearchQHFactory();
        final FilterItem channels = contentFilterNamed(factory, "channels");

        final List<FilterItem> filters = NewPipeTools.contentFiltersOrDefault(factory,
                List.of(channels.getIdentifier()));
        assertEquals(1, filters.size());
        assertSame(channels, filters.get(0));
    }

    @Test
    void unresolvableContentFilterIdsFallBackToTheDefault() {
        final SearchQueryHandlerFactory factory = ServiceList.YouTube.getSearchQHFactory();
        final List<FilterItem> filters = NewPipeTools.contentFiltersOrDefault(factory,
                List.of(9999));
        assertEquals(1, filters.size());
        assertEquals("all", filters.get(0).getName());
    }

    private static FilterItem contentFilterNamed(final SearchQueryHandlerFactory factory,
                                                 final String name) {
        for (final FilterGroup group : factory.getAvailableContentFilter().getFilterGroups()) {
            for (final FilterItem item : group.filterItems) {
                if (name.equals(item.getName())) {
                    return item;
                }
            }
        }
        throw new AssertionError("no content filter named " + name);
    }
}
