package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.request.GuessRequest;
import com.example.candles.dto.response.DailyRoundResponse;
import com.example.candles.dto.response.GuessResponse;
import com.example.candles.entity.GuessMode;
import com.example.candles.service.DailyRoundService;
import com.example.candles.service.RateLimiter;
import com.example.candles.service.RoundPlayService;

/**
 * The daily challenge: one chart a day, the same one for everybody, one attempt.
 *
 * Public like {@code /api/practice}. A signed-out visitor can play — nothing is recorded for
 * them, so their one attempt is only as strong as their browser, which is the same bargain
 * practice already makes and a better trade than putting a wallet in front of the thing the
 * whole feature exists to get people to try.
 */
@RestController
@RequestMapping("/api/daily")
public class DailyController {

    private final DailyRoundService dailyRoundService;
    private final RoundPlayService roundPlayService;
    private final CandlesProperties properties;
    private final RateLimiter rateLimiter;

    public DailyController(DailyRoundService dailyRoundService,
                           RoundPlayService roundPlayService,
                           CandlesProperties properties,
                           RateLimiter rateLimiter) {
        this.dailyRoundService = dailyRoundService;
        this.roundPlayService = roundPlayService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/round")
    public DailyRoundResponse round(HttpServletRequest request) {
        rateLimiter.check("daily-round", properties.round().rateLimit().roundsPerMinute(), request);
        return dailyRoundService.round(currentUserId());
    }

    /**
     * The recent past and how the caller did on each. Public like the round itself; a signed-out
     * visitor gets the list with nothing played on it.
     */
    @GetMapping("/archive")
    public List<DailyRoundService.ArchiveEntry> archive(
            @RequestParam(defaultValue = "14") int days, HttpServletRequest request) {
        rateLimiter.check("daily-archive", properties.round().rateLimit().roundsPerMinute(), request);
        return dailyRoundService.archive(currentUserId(), days);
    }

    @GetMapping("/archive/{day}")
    public DailyRoundResponse archiveRound(@PathVariable LocalDate day, HttpServletRequest request) {
        rateLimiter.check("daily-round", properties.round().rateLimit().roundsPerMinute(), request);
        return dailyRoundService.archiveRound(currentUserId(), day);
    }

    /**
     * The day travels in the path rather than the token, so it has to be checked back against
     * the token's chart — {@code checkIsArchived} is what makes the pair agree.
     */
    @PostMapping("/archive/{day}/guess")
    public GuessResponse archiveGuess(@PathVariable LocalDate day,
                                      @Valid @RequestBody GuessRequest body,
                                      HttpServletRequest request) {
        rateLimiter.check("daily-guess", properties.round().rateLimit().guessesPerMinute(), request);

        return roundPlayService.play(body, GuessMode.ARCHIVE,
                token -> dailyRoundService.checkIsArchived(token, day));
    }

    @PostMapping("/guess")
    public GuessResponse guess(@Valid @RequestBody GuessRequest body, HttpServletRequest request) {
        rateLimiter.check("daily-guess", properties.round().rateLimit().guessesPerMinute(), request);

        return roundPlayService.play(body, GuessMode.DAILY, dailyRoundService::checkIsToday);
    }

    /**
     * The filter authenticates as a bare {@code Long}; an unauthenticated request carries
     * Spring Security's anonymous token, whose principal is a String, so the match fails and
     * the caller is treated as signed out.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
