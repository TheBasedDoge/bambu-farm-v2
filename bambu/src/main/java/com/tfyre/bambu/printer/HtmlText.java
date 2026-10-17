package com.tfyre.bambu.printer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Etsy's API returns listing titles, buyer names and variation values HTML-encoded ({@code 6.5&quot; Door
 * Speaker}), so they showed up that way all over the app. {@link #plain} turns them back into text.
 * <p>
 * Only for what is shown. Mapping keys were saved with the encoded variation values in them
 * ({@code Size=2x 3&quot; inches}), so variations are kept as Etsy sends them and decoded where they are displayed;
 * decoding them at the source would stop saved per-variation mappings from matching new orders.
 */
public final class HtmlText {

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Map<String, String> NAMED = Map.of(
            "quot", "\"", "amp", "&", "apos", "'", "lt", "<", "gt", ">", "nbsp", " ",
            "rsquo", "'", "lsquo", "'", "rdquo", "\"", "ldquo", "\"");

    private HtmlText() {
    }

    public static String plain(final String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        final Matcher m = ENTITY.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (m.find()) {
            final String e = m.group(1);
            String replacement = null;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) {
                    replacement = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                } else if (e.startsWith("#")) {
                    replacement = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                } else {
                    replacement = NAMED.get(e.toLowerCase(java.util.Locale.ROOT));
                }
            } catch (IllegalArgumentException ex) {
                replacement = null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? m.group() : replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
