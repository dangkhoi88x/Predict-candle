package com.example.candles.service;

/**
 * Comments and layout out of a stylesheet, and nothing else.
 *
 * <b>Why so little.</b> Measured against esbuild on this project's stylesheet: esbuild gets it to
 * 17,609 bytes gzipped, this to 17,808 — 1% apart, because gzip already encodes the repetition a
 * full minifier removes. What is left after whitespace is shortening colours, merging rules and
 * dropping units, each of which can change what the browser draws. One percent is not worth
 * either a Node toolchain in a Maven build or a stylesheet that renders differently than its
 * source, which is why the JavaScript goes through a real compiler and this does not.
 *
 * <b>Two things it deliberately will not touch</b>, both of which a careless minifier gets wrong:
 *
 * <ul>
 *   <li><b>The space before a colon.</b> {@code .card :hover} is a descendant, {@code .card:hover}
 *       is the card itself. Space is only dropped beside the punctuation that cannot carry
 *       meaning — braces, semicolons and commas — and collapsed to one space everywhere else.</li>
 *   <li><b>Anything inside quotes.</b> A {@code content: "/* … *&#47;"} holds what looks like a
 *       comment, and an escaped quote inside a string does not end it.</li>
 * </ul>
 *
 * An unquoted {@code url(…)} containing spaces would be damaged, which CSS does not allow
 * unescaped and this project does not write — every font here is quoted.
 */
final class CssMinifier {

    private CssMinifier() {
    }

    /** Punctuation whose neighbouring space can never carry meaning. */
    private static boolean structural(char c) {
        return c == '{' || c == '}' || c == ';' || c == ',';
    }

    private static boolean space(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    static String minify(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        int n = css.length();
        char quote = 0;
        int depth = 0;

        while (i < n) {
            char c = css.charAt(i);

            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(css.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) quote = 0;
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }

            if (c == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }

            if (space(c)) {
                int j = i;
                while (j < n && space(css.charAt(j))) j++;
                char previous = out.isEmpty() ? 0 : out.charAt(out.length() - 1);
                char next = j < n ? css.charAt(j) : 0;
                boolean afterPropertyColon = previous == ':' && depth > 0;
                if (previous != 0 && next != 0 && !structural(previous) && !structural(next)
                        && !afterPropertyColon) {
                    out.append(' ');
                }
                i = j;
                continue;
            }

            if (c == '{') depth++;
            if (c == '}') {
                if (depth > 0) depth--;
                // The last semicolon in a block separates a declaration from nothing.
                if (!out.isEmpty() && out.charAt(out.length() - 1) == ';') out.setLength(out.length() - 1);
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }
}
