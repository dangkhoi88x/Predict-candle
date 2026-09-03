package com.example.candles.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.candles.dto.CandleDto;
import com.example.candles.dto.PatternExampleResponse;
import com.example.candles.pattern.PatternExample;
import com.example.candles.service.PatternExampleService;

@RestController
@RequestMapping("/api/patterns")
public class PatternExampleController {

    private final PatternExampleService patternExampleService;

    public PatternExampleController(PatternExampleService patternExampleService) {
        this.patternExampleService = patternExampleService;
    }

    @GetMapping("/{patternId}/example")
    public PatternExampleResponse example(@PathVariable String patternId, @RequestParam String asset) {
        PatternExample example = patternExampleService.findExample(patternId, asset);
        return new PatternExampleResponse(
                example.asset(),
                example.timeframe(),
                example.occurredAt(),
                example.candles().stream().map(CandleDto::from).toList(),
                example.patternStartIndex(),
                example.patternLength()
        );
    }
}
