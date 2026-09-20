package com.example.candles.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CssMinifierTest {

    @Test
    void commentsAndLayoutGo() {
        assertThat(CssMinifier.minify("""
                /* a note */
                .card {
                    color: red;
                    margin: 0 auto;
                }
                """)).isEqualTo(".card{color:red;margin:0 auto}");
    }

    @Test
    void theSpaceBeforeAColonSurvives() {
        // ".card :hover" is a descendant; ".card:hover" is the card. A minifier that drops this
        // space restyles the page silently.
        assertThat(CssMinifier.minify(".card :hover { color: red; }"))
                .isEqualTo(".card :hover{color:red}");
        assertThat(CssMinifier.minify(".card:hover { color: red; }"))
                .isEqualTo(".card:hover{color:red}");
    }

    @Test
    void whatLooksLikeACommentInsideAStringIsNotOne() {
        assertThat(CssMinifier.minify(".a::before { content: \"/* kept */\"; }"))
                .isEqualTo(".a::before{content:\"/* kept */\"}");
    }

    @Test
    void anEscapedQuoteDoesNotEndTheString() {
        assertThat(CssMinifier.minify(".a::before { content: \"say \\\"hi\\\"  now\"; }"))
                .isEqualTo(".a::before{content:\"say \\\"hi\\\"  now\"}");
    }

    @Test
    void selectorListsAndDescendantsKeepTheirMeaning() {
        // The combinator keeps its spaces on purpose: > alone would be safe to tighten, but + and
        // ~ also appear inside values, and one rule covering all three is one rule to get wrong.
        assertThat(CssMinifier.minify("""
                .a .b,
                .c > .d {
                    gap: 4px;
                }
                """)).isEqualTo(".a .b,.c > .d{gap:4px}");
    }

    @Test
    void modernSyntaxIsCarriedThroughUntouched() {
        // The things a 2010-era minifier mangles: color-mix, custom properties, media features.
        assertThat(CssMinifier.minify("""
                @media (hover: hover) and (pointer: fine) {
                    :root {
                        --tint: color-mix(in oklab, var(--accent) 12%, transparent);
                    }
                }
                """)).isEqualTo("@media (hover: hover) and (pointer: fine){"
                // The space after a comma inside a function goes: CSS does not read it. The one
                // after a media feature's colon stays, being outside any block.
                + ":root{--tint:color-mix(in oklab,var(--accent) 12%,transparent)}}");
    }

    @Test
    void anUnclosedCommentTakesTheRestRatherThanThrowing() {
        assertThat(CssMinifier.minify(".a{color:red} /* unfinished")).isEqualTo(".a{color:red}");
    }
}
