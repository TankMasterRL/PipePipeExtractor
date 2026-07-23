package org.schabi.newpipe.mcp;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.feed.FeedInfo;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps the extractor's {@code Info}/{@code InfoItem} model to plain, JSON-serializable maps and
 * lists, adding opaque {@code nextPageToken}s (built via {@link Cursor}) to paginated results.
 */
final class Serializer {

    private final Cursor cursor;

    Serializer(final Cursor cursor) {
        this.cursor = cursor;
    }

    Map<String, Object> service(final StreamingService service) {
        final List<String> capabilities = new ArrayList<>();
        for (final StreamingService.ServiceInfo.MediaCapability capability
                : service.getServiceInfo().getMediaCapabilities()) {
            capabilities.add(capability.name());
        }
        final Map<String, Object> map = Json.obj(
                "id", service.getServiceId(),
                "name", service.getServiceInfo().getName(),
                "capabilities", capabilities,
                "searchContentFilters",
                        filters(service.getSearchQHFactory().getAvailableContentFilter()),
                "searchSortFilters",
                        filters(service.getSearchQHFactory().getAvailableSortFilter()));
        try {
            final KioskList kioskList = service.getKioskList();
            final List<String> kiosks = new ArrayList<>(kioskList.getAvailableKiosks());
            if (!kiosks.isEmpty()) {
                map.put("kiosks", kiosks);
            }
            if (kioskList.getDefaultKioskId() != null) {
                map.put("defaultKiosk", kioskList.getDefaultKioskId());
            }
        } catch (final Exception e) {
            // Kiosk information is optional in the service description.
        }
        return map;
    }

    Map<String, Object> streamInfo(final StreamInfo info) {
        return Json.obj(
                "serviceId", info.getServiceId(),
                "id", info.getId(),
                "url", info.getUrl(),
                "name", info.getName(),
                "streamType", enumName(info.getStreamType()),
                "duration", info.getDuration(),
                "viewCount", info.getViewCount(),
                "likeCount", info.getLikeCount(),
                "dislikeCount", info.getDislikeCount(),
                "uploaderName", emptyToNull(info.getUploaderName()),
                "uploaderUrl", emptyToNull(info.getUploaderUrl()),
                "uploaderVerified", info.isUploaderVerified(),
                "uploaderSubscriberCount", info.getUploaderSubscriberCount(),
                "uploaderAvatars", images(info.getUploaderAvatars()),
                "subChannelName", emptyToNull(info.getSubChannelName()),
                "subChannelUrl", emptyToNull(info.getSubChannelUrl()),
                "textualUploadDate", info.getTextualUploadDate(),
                "thumbnails", images(info.getThumbnails()),
                "description", description(info.getDescription()),
                "category", emptyToNull(info.getCategory()),
                "licence", emptyToNull(info.getLicence()),
                "tags", nullIfEmpty(info.getTags()),
                "ageLimit", info.getAgeLimit(),
                "host", emptyToNull(info.getHost()),
                "hlsUrl", emptyToNull(info.getHlsUrl()),
                "dashMpdUrl", emptyToNull(info.getDashMpdUrl()),
                "audioStreams", audioStreams(info.getAudioStreams()),
                "videoStreams", videoStreams(info.getVideoStreams()),
                "videoOnlyStreams", videoStreams(info.getVideoOnlyStreams()),
                "relatedItems", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
    }

    Map<String, Object> channelInfo(final ChannelInfo info) throws java.io.IOException {
        final List<Object> tabs = new ArrayList<>();
        for (final ListLinkHandler tab : info.getTabs()) {
            final String tabName = tab.getContentFilters().isEmpty()
                    ? "" : tab.getContentFilters().get(0).getName();
            tabs.add(Json.obj(
                    "name", tabName,
                    "token", cursor.tabNavigation(info.getServiceId(), tab)));
        }
        return Json.obj(
                "serviceId", info.getServiceId(),
                "id", info.getId(),
                "url", info.getUrl(),
                "name", info.getName(),
                "description", emptyToNull(info.getDescription()),
                "subscriberCount", info.getSubscriberCount(),
                "verified", info.isVerified(),
                "avatars", images(info.getAvatars()),
                "banners", images(info.getBanners()),
                "parentChannelName", emptyToNull(info.getParentChannelName()),
                "parentChannelUrl", emptyToNull(info.getParentChannelUrl()),
                "tags", nullIfEmpty(info.getTags()),
                "tabs", tabs.isEmpty() ? null : tabs,
                "errors", errors(info.getErrors()));
    }

    Map<String, Object> channelTabInfo(final ChannelTabInfo info) throws java.io.IOException {
        final Map<String, Object> map = Json.obj(
                "serviceId", info.getServiceId(),
                "url", info.getUrl(),
                "items", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
        if (info.hasNextPage()) {
            final ListLinkHandler handler = new ListLinkHandler(
                    info.getOriginalUrl(), info.getUrl(), info.getId(),
                    info.getContentFilters(), info.getSortFilter());
            map.put("nextPageToken",
                    cursor.channelTabNext(info.getServiceId(), handler, info.getNextPage()));
        }
        return map;
    }

    Map<String, Object> playlistInfo(final PlaylistInfo info) throws java.io.IOException {
        final Map<String, Object> map = Json.obj(
                "serviceId", info.getServiceId(),
                "id", info.getId(),
                "url", info.getUrl(),
                "name", info.getName(),
                "uploaderName", emptyToNull(info.getUploaderName()),
                "uploaderUrl", emptyToNull(info.getUploaderUrl()),
                "streamCount", info.getStreamCount(),
                "playlistType", enumName(info.getPlaylistType()),
                "thumbnailUrl", emptyToNull(info.getThumbnailUrl()),
                "items", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
        if (info.hasNextPage()) {
            map.put("nextPageToken", cursor.urlListNext(
                    "playlist", info.getServiceId(), info.getUrl(), info.getNextPage()));
        }
        return map;
    }

    Map<String, Object> commentsInfo(final CommentsInfo info) throws java.io.IOException {
        final Map<String, Object> map = Json.obj(
                "serviceId", info.getServiceId(),
                "url", info.getUrl(),
                "commentsDisabled", info.isCommentsDisabled(),
                "items", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
        if (info.hasNextPage()) {
            map.put("nextPageToken", cursor.urlListNext(
                    "comments", info.getServiceId(), info.getUrl(), info.getNextPage()));
        }
        return map;
    }

    Map<String, Object> searchInfo(final SearchInfo info) throws java.io.IOException {
        final Map<String, Object> map = Json.obj(
                "serviceId", info.getServiceId(),
                "searchString", info.getSearchString(),
                "searchSuggestion", emptyToNull(info.getSearchSuggestion()),
                "isCorrectedSearch", info.isCorrectedSearch(),
                "items", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
        if (info.hasNextPage()) {
            map.put("nextPageToken", cursor.searchNext(info.getServiceId(),
                    info.getSearchString(), filterIds(info.getContentFilters()),
                    filterIds(info.getSortFilter()), info.getNextPage()));
        }
        return map;
    }

    Map<String, Object> kioskInfo(final KioskInfo info) throws java.io.IOException {
        final Map<String, Object> map = Json.obj(
                "serviceId", info.getServiceId(),
                "id", info.getId(),
                "url", info.getUrl(),
                "name", info.getName(),
                "items", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
        if (info.hasNextPage()) {
            map.put("nextPageToken", cursor.urlListNext(
                    "kiosk", info.getServiceId(), info.getUrl(), info.getNextPage()));
        }
        return map;
    }

    Map<String, Object> feedInfo(final FeedInfo info) {
        return Json.obj(
                "serviceId", info.getServiceId(),
                "id", info.getId(),
                "url", info.getUrl(),
                "name", info.getName(),
                "items", infoItems(info.getRelatedItems()),
                "errors", errors(info.getErrors()));
    }

    Map<String, Object> itemsPage(final ListExtractor.InfoItemsPage<? extends InfoItem> page,
                                  final String nextPageToken) {
        final Map<String, Object> map = Json.obj(
                "items", infoItems(page.getItems()),
                "errors", errors(page.getErrors()));
        if (nextPageToken != null) {
            map.put("nextPageToken", nextPageToken);
        }
        return map;
    }

    private List<Object> infoItems(final List<? extends InfoItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        final List<Object> result = new ArrayList<>();
        for (final InfoItem item : items) {
            result.add(infoItem(item));
        }
        return result;
    }

    private Map<String, Object> infoItem(final InfoItem item) {
        if (item instanceof StreamInfoItem) {
            return streamItem((StreamInfoItem) item);
        }
        if (item instanceof ChannelInfoItem) {
            return channelItem((ChannelInfoItem) item);
        }
        if (item instanceof PlaylistInfoItem) {
            return playlistItem((PlaylistInfoItem) item);
        }
        if (item instanceof CommentsInfoItem) {
            return commentItem((CommentsInfoItem) item);
        }
        return Json.obj(
                "type", enumName(item.getInfoType()),
                "serviceId", item.getServiceId(),
                "url", item.getUrl(),
                "name", item.getName(),
                "thumbnailUrl", emptyToNull(item.getThumbnailUrl()));
    }

    private Map<String, Object> streamItem(final StreamInfoItem item) {
        return Json.obj(
                "type", "STREAM",
                "serviceId", item.getServiceId(),
                "url", item.getUrl(),
                "name", item.getName(),
                "streamType", enumName(item.getStreamType()),
                "uploaderName", item.getUploaderName(),
                "uploaderUrl", item.getUploaderUrl(),
                "uploaderVerified", item.isUploaderVerified(),
                "duration", item.getDuration(),
                "viewCount", item.getViewCount(),
                "textualUploadDate", item.getTextualUploadDate(),
                "shortDescription", item.getShortDescription(),
                "thumbnailUrl", emptyToNull(item.getThumbnailUrl()));
    }

    private Map<String, Object> channelItem(final ChannelInfoItem item) {
        return Json.obj(
                "type", "CHANNEL",
                "serviceId", item.getServiceId(),
                "url", item.getUrl(),
                "name", item.getName(),
                "description", emptyToNull(item.getDescription()),
                "subscriberCount", item.getSubscriberCount(),
                "streamCount", item.getStreamCount(),
                "verified", item.isVerified(),
                "thumbnailUrl", emptyToNull(item.getThumbnailUrl()));
    }

    private Map<String, Object> playlistItem(final PlaylistInfoItem item) {
        return Json.obj(
                "type", "PLAYLIST",
                "serviceId", item.getServiceId(),
                "url", item.getUrl(),
                "name", item.getName(),
                "uploaderName", item.getUploaderName(),
                "streamCount", item.getStreamCount(),
                "playlistType", enumName(item.getPlaylistType()),
                "thumbnailUrl", emptyToNull(item.getThumbnailUrl()));
    }

    private Map<String, Object> commentItem(final CommentsInfoItem item) {
        return Json.obj(
                "type", "COMMENT",
                "serviceId", item.getServiceId(),
                "url", item.getUrl(),
                "commentId", item.getCommentId(),
                "text", emptyToNull(item.getCommentText()),
                "uploaderName", item.getUploaderName(),
                "uploaderUrl", item.getUploaderUrl(),
                "uploaderVerified", item.isUploaderVerified(),
                "textualUploadDate", item.getTextualUploadDate(),
                "likeCount", item.getLikeCount() == CommentsInfoItem.NO_LIKE_COUNT
                        ? null : item.getLikeCount(),
                "textualLikeCount", emptyToNull(item.getTextualLikeCount()),
                "pinned", item.isPinned(),
                "heartedByUploader", item.isHeartedByUploader(),
                "replyCount", item.getReplyCount() == CommentsInfoItem.UNKNOWN_REPLY_COUNT
                        ? null : item.getReplyCount(),
                "hasReplies", item.getReplies() != null);
    }

    private static List<Object> audioStreams(final List<AudioStream> streams) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        final List<Object> result = new ArrayList<>();
        for (final AudioStream stream : streams) {
            result.add(Json.obj(
                    "id", cleanId(stream.getId()),
                    "format", formatName(stream.getFormat()),
                    "averageBitrate", nonNegativeOrNull(stream.getAverageBitrate()),
                    "codec", emptyToNull(stream.getCodec()),
                    "deliveryMethod", stream.getDeliveryMethod().name(),
                    "url", stream.isUrl() ? stream.getContent() : null,
                    "manifestUrl", stream.getManifestUrl()));
        }
        return result;
    }

    private static List<Object> videoStreams(final List<VideoStream> streams) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        final List<Object> result = new ArrayList<>();
        for (final VideoStream stream : streams) {
            result.add(Json.obj(
                    "id", cleanId(stream.getId()),
                    "resolution", emptyToNull(stream.getResolution()),
                    "format", formatName(stream.getFormat()),
                    "videoOnly", stream.isVideoOnly(),
                    "fps", nonNegativeOrNull(stream.getFps()),
                    "codec", emptyToNull(stream.getCodec()),
                    "deliveryMethod", stream.getDeliveryMethod().name(),
                    "url", stream.isUrl() ? stream.getContent() : null,
                    "manifestUrl", stream.getManifestUrl()));
        }
        return result;
    }

    private static List<Object> images(final List<Image> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        final List<Object> result = new ArrayList<>();
        for (final Image image : images) {
            final int width = image.getWidth();
            final int height = image.getHeight();
            result.add(Json.obj(
                    "url", image.getUrl(),
                    "width", width == Image.WIDTH_UNKNOWN ? null : width,
                    "height", height == Image.HEIGHT_UNKNOWN ? null : height,
                    "resolutionLevel", image.getEstimatedResolutionLevel().name()));
        }
        return result;
    }

    private static Map<String, Object> description(final Description description) {
        if (description == null) {
            return null;
        }
        return Json.obj("content", description.getContent(),
                "type", descriptionType(description.getType()));
    }

    private static String descriptionType(final int type) {
        switch (type) {
            case Description.HTML:
                return "HTML";
            case Description.MARKDOWN:
                return "MARKDOWN";
            case Description.PLAIN_TEXT:
                return "PLAIN_TEXT";
            default:
                return String.valueOf(type);
        }
    }

    private static List<Object> filters(final Filter filter) {
        if (filter == null || filter.getFilterGroups() == null) {
            return null;
        }
        final List<Object> result = new ArrayList<>();
        for (final FilterGroup group : filter.getFilterGroups()) {
            if (group == null || group.filterItems == null) {
                continue;
            }
            for (final FilterItem item : group.filterItems) {
                if (item == null) {
                    continue;
                }
                result.add(Json.obj(
                        "id", item.getIdentifier(),
                        "name", item.getName(),
                        "group", emptyToNull(group.groupName)));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static List<Integer> filterIds(final List<FilterItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        final List<Integer> ids = new ArrayList<>();
        for (final FilterItem item : items) {
            ids.add(item.getIdentifier());
        }
        return ids;
    }

    private static List<String> errors(final List<Throwable> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        final List<String> result = new ArrayList<>();
        for (final Throwable throwable : errors) {
            result.add(throwable.toString());
        }
        return result;
    }

    private static List<String> nullIfEmpty(final List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private static String emptyToNull(final String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String cleanId(final String id) {
        return id == null || id.isEmpty() || id.equals(Stream.ID_UNKNOWN) ? null : id;
    }

    private static String formatName(final MediaFormat format) {
        return format == null ? null : format.name();
    }

    private static Integer nonNegativeOrNull(final int value) {
        return value < 0 ? null : value;
    }

    private static String enumName(final Enum<?> value) {
        return value == null ? null : value.name();
    }
}
