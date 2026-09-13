package com.example.candles.controller;

import com.example.candles.dto.response.AdminChallengeDetail;
import com.example.candles.dto.response.AdminChallenges;
import com.example.candles.service.AdminChallengeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * What the daily challenge and the pattern quiz will ask, day by day.
 *
 * Read-only, and it has to be: both questions are functions of the date, so there is nothing
 * here to edit. Changing tomorrow's chart would mean changing the seed, and the seed is the
 * date. What this offers instead is the thing that was missing — finding out in advance that a
 * day cannot be built, rather than at midnight along with everybody else.
 */
@RestController
@RequestMapping("/api/admin/challenges")
public class AdminChallengeController {

    private final AdminChallengeService challenges;

    public AdminChallengeController(AdminChallengeService challenges) {
        this.challenges = challenges;
    }

    @GetMapping
    public AdminChallenges list(@RequestParam(required = false) Integer back,
                                 @RequestParam(required = false) Integer ahead,
                                 @RequestParam(required = false, defaultValue = "false") boolean fresh) {
        return challenges.list(back, ahead, fresh);
    }

    /** One day with both charts — the daily's window and answers, and the quiz's slice. */
    @GetMapping("/{day}")
    public AdminChallengeDetail detail(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return challenges.detail(day);
    }
}
