package org.schabi.newpipe.mcp;

import org.schabi.newpipe.extractor.Extractor;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

/**
 * The language and content country a single tool call should extract in.
 *
 * <p>This exists because a service is free to ignore {@link
 * org.schabi.newpipe.extractor.NewPipe#getPreferredLocalization()}, and YouTube does: its
 * {@code getLocalization()} returns {@code zu} so that YouTube hands back original, untranslated
 * video titles. That is the right default for a player UI, which renders its own strings, and the
 * wrong one for a headless consumer like this server, where YouTube's own strings — textual upload
 * dates, view and subscriber counts, item counts — are part of the payload and would arrive in
 * Zulu.</p>
 *
 * <p>The override that a service cannot ignore is {@link Extractor#forceLocalization}, so this
 * class applies the caller's choice per extractor rather than by mutating the process-wide
 * preference. That also keeps concurrent tool calls independent: two requests asking for different
 * languages touch no shared state.</p>
 */
final class Localizations {

    /** No preference: every extractor keeps whatever its own service decides. */
    static final Localizations NONE = new Localizations(null, null);

    private final Localization localization;
    private final ContentCountry contentCountry;

    private Localizations(final Localization localization,
                          final ContentCountry contentCountry) {
        this.localization = localization;
        this.contentCountry = contentCountry;
    }

    /**
     * Builds a preference from a language and a country as a caller writes them.
     *
     * <p>{@code language} is an ISO 639-1 code that may carry a region ({@code en}, {@code en-GB},
     * and {@code en_GB} for tolerance). A region there and a separate {@code country} are both
     * accepted: the explicit {@code country} wins for the content country, and supplies the
     * language's region when the language code carries none.</p>
     *
     * @param language the requested language, or null/blank for no preference
     * @param country  the requested content country, or null/blank for no preference
     * @return the resulting preference, or {@link #NONE} when neither was given
     */
    static Localizations of(final String language, final String country) {
        final String languageCode = trimToNull(language);
        final String countryCode = trimToNull(country);
        if (languageCode == null && countryCode == null) {
            return NONE;
        }

        Localization resolved = null;
        if (languageCode != null) {
            resolved = Localization.fromLocalizationCode(languageCode.replace('_', '-'));
            if (countryCode != null && resolved.getCountryCode().isEmpty()) {
                resolved = new Localization(resolved.getLanguageCode(), countryCode);
            }
        }

        final String resolvedCountry;
        if (countryCode != null) {
            resolvedCountry = countryCode;
        } else if (resolved != null && !resolved.getCountryCode().isEmpty()) {
            resolvedCountry = resolved.getCountryCode();
        } else {
            resolvedCountry = null;
        }

        return new Localizations(resolved,
                resolvedCountry == null ? null : new ContentCountry(resolvedCountry));
    }

    boolean isEmpty() {
        return localization == null && contentCountry == null;
    }

    /**
     * The requested localization, or the extractor's own default. Used for
     * {@link org.schabi.newpipe.extractor.NewPipe#init}, which takes no nulls and where "no
     * preference" and "the default" are the same thing.
     */
    Localization localizationOrDefault() {
        return localization == null ? Localization.DEFAULT : localization;
    }

    /** The requested content country, or the extractor's own default. */
    ContentCountry contentCountryOrDefault() {
        return contentCountry == null ? ContentCountry.DEFAULT : contentCountry;
    }

    /** Applies whichever halves were requested, leaving the rest at the service's own default. */
    void applyTo(final Extractor extractor) {
        if (localization != null) {
            extractor.forceLocalization(localization);
        }
        if (contentCountry != null) {
            extractor.forceContentCountry(contentCountry);
        }
    }

    /**
     * The same for a {@link SuggestionExtractor}, which carries the identical pair of setters
     * without sharing a base class with {@link Extractor}.
     */
    void applyTo(final SuggestionExtractor extractor) {
        if (localization != null) {
            extractor.forceLocalization(localization);
        }
        if (contentCountry != null) {
            extractor.forceContentCountry(contentCountry);
        }
    }

    /** The language as {@link #of} would take it back, or null. Used to round-trip through a page
     * token so continuations stay in the language the first page was fetched in. */
    String language() {
        return localization == null ? null : localization.getLocalizationCode();
    }

    /** The content country as {@link #of} would take it back, or null. */
    String country() {
        return contentCountry == null ? null : contentCountry.getCountryCode();
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
