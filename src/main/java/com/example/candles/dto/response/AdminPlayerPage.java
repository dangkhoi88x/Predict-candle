package com.example.candles.dto.response;

import java.util.List;

/**
 * One page of the admin player list.
 *
 * The list used to be every account, read into memory, tallied and sorted in Java. That is fine
 * at fifty accounts and stops being fine without ever announcing it: the page just takes longer
 * every week, and the first thing anyone notices is an admin screen that times out.
 *
 * {@code total} is the count the same filter matches, not the count of accounts — the pager and
 * the "N tài khoản" line both mean "matching what you typed", and a headcount that ignored the
 * search box would contradict the rows underneath it.
 */
public record AdminPlayerPage(List<PlayerSummary> players, String query, String sort,
                               int page, int size, long total, boolean hasMore) {
}
