package com.tfyre.bambu.printer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Guesses which library file a marketplace listing prints, from the two names alone.
 * <p>
 * The shop's files are named for what they are ({@code S2000_Door_Speaker_Adapter.gcode.3mf}) and the listings
 * are titled for search ("6.5" Door Speaker Adapter for Honda (S2000, Prelude, CRX, Etc) (Set of 2)"), so the
 * words of the file name nearly always appear in the title. The score is simply the share of the file name's
 * words found in the title; ties go to the file with more words matched, i.e. the more specific name.
 * <p>
 * It is a <b>suggestion</b>: the Mappings tab opens the editor pre-filled and a person saves it. It is wrong
 * often enough that it must never be applied by itself - a kit, or two products that differ by one word the
 * title doesn't use, will pick the neighbour.
 */
public final class MappingSuggester {

    private static final Set<String> STOP = Set.of("for", "the", "and", "with", "of", "to", "a", "in", "fits", "fit",
            "etc", "gcode", "3mf", "v", "x");
    private static final Map<String, String> ALIAS = Map.of("s2k", "s2000");
    /** At least this share of the file name's words must be in the title. */
    private static final double MIN_SCORE = 0.6;

    private MappingSuggester() {
    }

    /** Words of a name, in order: lower case, split on anything that is not a letter or digit and on camelCase. */
    static List<String> words(final String name) {
        String s = name == null ? "" : name;
        s = s.replaceAll("(?i)\\.gcode\\.3mf$|\\.3mf$|\\.gcode$", "");
        s = s.replaceAll("([a-z])([A-Z])", "$1 $2");
        final List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.isEmpty()) {
                continue;
            }
            t = ALIAS.getOrDefault(t, t);
            if (t.length() > 3 && t.endsWith("s")) {
                t = t.substring(0, t.length() - 1); // pods -> pod, adapters -> adapter
            }
            if (!STOP.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The library file whose name best matches {@code title}, or empty when nothing matches well enough.
     *
     * @param files library paths as the mappings store them ("x.gcode.3mf" or "Project/x.gcode.3mf")
     */
    public static Optional<String> suggest(final String title, final List<String> files) {
        final List<String> titleWords = words(title);
        final Set<String> inTitle = new LinkedHashSet<>(titleWords);
        // "cup holder" in a title is "cupholder" in a file name.
        for (int i = 0; i + 1 < titleWords.size(); i++) {
            inTitle.add(titleWords.get(i) + titleWords.get(i + 1));
        }
        String best = null;
        double bestScore = 0;
        int bestMatched = 0;
        for (final String file : files) {
            final String base = file.substring(file.lastIndexOf('/') + 1);
            final Set<String> fileWords = new LinkedHashSet<>(words(base));
            if (fileWords.isEmpty()) {
                continue;
            }
            int matched = 0;
            for (final String w : fileWords) {
                if (inTitle.contains(w) || inTitle.stream().anyMatch(t -> w.length() >= 4 && t.length() >= 4
                        && (t.startsWith(w) || w.startsWith(t)))) {
                    matched++;
                }
            }
            final double score = (double) matched / fileWords.size();
            if (matched < 2 || score < MIN_SCORE) {
                continue;
            }
            if (score > bestScore || (score == bestScore && matched > bestMatched)) {
                best = file;
                bestScore = score;
                bestMatched = matched;
            }
        }
        return Optional.ofNullable(best);
    }

    /** How many prints one sale of a listing with this title takes: 2 for "set of 2", "pair", otherwise 1. */
    public static int copiesFor(final String title) {
        final String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.matches(".*(set of 2|set of two|\\bpair\\b|\\(2\\)|\\b2 ?pcs?\\b|\\b2[- ]pack\\b).*") ? 2 : 1;
    }
}
