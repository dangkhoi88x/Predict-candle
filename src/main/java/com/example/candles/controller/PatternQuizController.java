package com.example.candles.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.request.PatternGuessRequest;
import com.example.candles.dto.response.PatternQuizResponse;
import com.example.candles.service.PatternQuizService;
import com.example.candles.service.RateLimiter;

/**
 * The day's pattern quiz. Reading it is public — the question is the same for everyone and the
 * answer is withheld either way — but answering needs an account, because an answer that is not
 * recorded cannot be held to one a day and would not reach the streak it is supposed to feed.
 * That is the same line {@code POST /api/live/predict} draws, enforced in SecurityConfig.
 */
@RestController
@RequestMapping("/api/pattern-quiz")
public class PatternQuizController {

    private final PatternQuizService quizService;
    private final CandlesProperties properties;
    private final RateLimiter rateLimiter;

    public PatternQuizController(PatternQuizService quizService,
                                 CandlesProperties properties,
                                 RateLimiter rateLimiter) {
        this.quizService = quizService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/today")
    public PatternQuizResponse today(HttpServletRequest request) {
        rateLimiter.check("pattern-quiz", properties.round().rateLimit().roundsPerMinute(), request);
        return quizService.today(currentUserId());
    }

    @PostMapping("/answer")
    public PatternQuizResponse answer(@Valid @RequestBody PatternGuessRequest body,
                                      HttpServletRequest request) {
        rateLimiter.check("pattern-quiz-answer", properties.round().rateLimit().guessesPerMinute(), request);
        return quizService.answer(currentUserId(), body.patternId());
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
