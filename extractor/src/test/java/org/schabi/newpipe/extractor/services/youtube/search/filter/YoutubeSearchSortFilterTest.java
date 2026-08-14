package org.schabi.newpipe.extractor.services.youtube.search.filter;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.search.filter.protobuf.DateFilter;
import org.schabi.newpipe.extractor.services.youtube.search.filter.protobuf.SearchRequest;
import org.schabi.newpipe.extractor.services.youtube.search.filter.protobuf.SortOrder;
import org.schabi.newpipe.extractor.services.youtube.search.filter.protobuf.TypeFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeSearchSortFilterTest {

    @Test
    void spRoundTripsThroughDecodeSp() throws Exception {
        final YoutubeSearchSortFilter filter = new YoutubeSearchSortFilter.Builder()
                .setSortOrder(SortOrder.date)
                .setDateFilter(DateFilter.hour)
                .setTypeFilter(TypeFilter.video)
                .build();

        final SearchRequest decoded = filter.decodeSp(filter.getSp());

        assertEquals(SortOrder.date.getValue(), decoded.sorted.intValue());
        assertEquals(DateFilter.hour.getValue(), decoded.filter.date.intValue());
        assertEquals(TypeFilter.video.getValue(), decoded.filter.type.intValue());
    }

    /**
     * The path a search with a selected sort filter actually takes: any selection at all makes
     * {@link YoutubeFilters#evaluateSelectedFilters(String)} build an 'sp' parameter, so an
     * encoder that cannot run takes every sort filter down with it.
     */
    @Test
    void searchUrlCarriesTheSelectedSortFilter() throws Exception {
        final YoutubeSearchQueryHandlerFactory factory =
                YoutubeSearchQueryHandlerFactory.getInstance();

        final String url = factory.getUrl("test query",
                List.of(itemNamed(factory.getAvailableContentFilter(), YoutubeFilters.ALL)),
                List.of(itemNamed(factory.getAvailableSortFilter(), "past_hour")));

        assertTrue(url.startsWith(
                "https://www.youtube.com/results?search_query=test+query&sp="), url);
    }

    private static FilterItem itemNamed(final Filter filter, final String name) {
        for (final FilterGroup group : filter.getFilterGroups()) {
            for (final FilterItem item : group.filterItems) {
                if (name.equals(item.getName())) {
                    return item;
                }
            }
        }
        throw new IllegalArgumentException("no filter item named " + name);
    }
}
