package com.meguri.core.daily;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/v1/daily/ai-news")
public final class AiNewsController {
    private final AiNewsService news;
    public AiNewsController(AiNewsService news) { this.news = news; }
    @GetMapping("/status")
    public Map<String, Object> status() { return news.status(); }
}
