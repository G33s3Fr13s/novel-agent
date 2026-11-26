package com.novelagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 联网搜索：通过自部署 SearXNG 聚合多引擎搜索结果，零 API key。
 * SearXNG 内部自动爬 Bing/百度/Google 等，返回结构化 JSON。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final LlmService llmService;

    private static final String SEARXNG_URL =
            System.getenv().getOrDefault("SEARXNG_URL", "http://localhost:8088") + "/search";
    private static final int MAX_RESULTS = 10;
    private static final int FETCH_COUNT = 3;
    private static final int TIMEOUT_MS = 30000;
    private static final int MAX_BODY_CHARS = 3000;
    /** 两次搜索最小间隔（毫秒），避免百度 CAPTCHA */
    private static final long MIN_SEARCH_INTERVAL_MS = 5000;
    private static volatile long lastSearchTime = 0;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Map<String, String> SITE_OPTIONS = Map.of(
            "qidian", "qidian.com",
            "fanqie", "fanqienovel.com",
            "qimao", "qimao.com",
            "jjwxc", "jjwxc.net"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Query optimization ─────────────────────────────────

    public String optimizeQuery(String userMessage) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是搜索引擎关键词提取器。将用户输入提炼为5-20字关键词，去掉口语化表达，保留核心实体和数字。只输出关键词。"),
                    Map.of("role", "user", "content", userMessage)
            );
            String result = llmService.chat(messages, 200);
            String cleaned = result.trim()
                    .replaceAll("^关键词[：:]?\\s*", "");
            if (cleaned.isBlank() || cleaned.length() < 2) return userMessage;
            log.info("Query optimized: '{}' -> '{}'", userMessage, cleaned);
            return cleaned;
        } catch (Exception e) {
            log.warn("Query optimization failed: {}", e.getMessage());
            return userMessage;
        }
    }

    // ── Search (SearXNG JSON API) ─────────────────────────

    public List<Map<String, String>> search(String query, List<String> siteKeys) {
        List<Map<String, String>> results = new ArrayList<>();
        try {
            // 速率限制，避免连续请求触发百度 CAPTCHA
            long now = System.currentTimeMillis();
            long elapsed = now - lastSearchTime;
            if (elapsed < MIN_SEARCH_INTERVAL_MS) {
                long waitMs = MIN_SEARCH_INTERVAL_MS - elapsed;
                log.debug("Rate limit: waiting {}ms before search", waitMs);
                Thread.sleep(waitMs);
            }

            String fullQuery = buildFullQuery(query, siteKeys);
            String url = SEARXNG_URL + "?format=json&q="
                    + URLEncoder.encode(fullQuery, StandardCharsets.UTF_8) + "&language=zh&engines=baidu,360search";
            log.info("SearXNG: {}", fullQuery);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.warn("SearXNG returned HTTP {}", resp.statusCode());
                return results;
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode items = root.get("results");
            if (items == null || !items.isArray()) {
                log.warn("SearXNG returned no results array, unresponsive: {}",
                        root.get("unresponsive_engines"));
                return results;
            }

            for (JsonNode item : items) {
                String title = item.has("title") ? item.get("title").asText().trim() : "";
                String href = item.has("url") ? item.get("url").asText().trim() : "";
                String snippet = item.has("content") ? item.get("content").asText().trim() : "";

                if (title.isEmpty()) continue;
                // 域名二次过滤（site 限定模式下）；选了「全网搜索」则跳过
                if (siteKeys != null && !siteKeys.isEmpty()
                        && !siteKeys.contains("web")
                        && !matchesAnySite(href, title, snippet, siteKeys)) continue;

                Map<String, String> r = new LinkedHashMap<>();
                r.put("title", title);
                r.put("url", href);
                r.put("snippet", snippet);
                results.add(r);
                if (results.size() >= MAX_RESULTS) break;
            }

            log.info("SearXNG returned {} results (filtered from {})",
                    results.size(), items.size());
            lastSearchTime = System.currentTimeMillis();
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
                log.info("  [{}/{}] title: {}", i + 1, results.size(), r.get("title"));
                log.info("         url: {}", r.get("url"));
            }
        } catch (Exception e) {
            log.warn("SearXNG search failed for '{}': {}", query, e.getMessage());
            lastSearchTime = System.currentTimeMillis();
        }
        return results;
    }

    // ── Content fetching ───────────────────────────────────

    private String fetchPageContent(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(TIMEOUT_MS).userAgent(UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9").get();
            Element main = doc.selectFirst("article");
            if (main == null) main = doc.selectFirst("main");
            if (main == null) main = doc.selectFirst("[role=main]");
            String[] junk = {"script","style","noscript","nav","header","footer","aside","iframe","svg","form"};
            for (String tag : junk) {
                for (Element el : (main != null ? main : doc).select(tag)) el.remove();
            }
            String text = (main != null ? main : doc.body()).text();
            if (text.length() > MAX_BODY_CHARS) text = text.substring(0, MAX_BODY_CHARS);
            return text;
        } catch (Exception e) {
            log.debug("Failed to fetch page content from {}: {}", url, e.getMessage());
            return null;
        }
    }

    public String toContextString(List<Map<String, String>> results) {
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【联网搜索结果】\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append(i + 1).append(". **").append(r.get("title")).append("**\n");
            sb.append("   来源: ").append(r.get("url")).append("\n");
            if (i < FETCH_COUNT) {
                String fullText = fetchPageContent(r.get("url"));
                sb.append(fullText != null && !fullText.isBlank()
                        ? "   正文摘要: " + fullText + "\n"
                        : "   摘要: " + r.get("snippet") + "\n");
            } else {
                sb.append("   摘要: ").append(r.get("snippet")).append("\n");
            }
        }
        return sb.toString();
    }

    // ── Internal helpers ────────────────────────────────────

    private String buildFullQuery(String query, List<String> siteKeys) {
        String filter = buildSiteFilter(siteKeys);
        return filter.isEmpty() ? query : query + " " + filter;
    }

    private String buildSiteFilter(List<String> siteKeys) {
        if (siteKeys == null || siteKeys.isEmpty()) return "";
        if (siteKeys.contains("web")) return "";
        // 单站点用 site: 语法精准搜索；多站点时拼域名关键词让百度/360 自己匹配
        if (siteKeys.size() == 1) {
            String domain = SITE_OPTIONS.get(siteKeys.get(0));
            if (domain != null) return "site:" + domain;
            return "";
        }
        // 多站点：域名当关键词，结果用 matchesAnySite 二次过滤
        StringBuilder sb = new StringBuilder();
        for (String key : siteKeys) {
            String domain = SITE_OPTIONS.get(key);
            if (domain == null) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(domain);
        }
        return sb.toString();
    }

    private boolean matchesAnySite(String href, String title, String snippet, List<String> siteKeys) {
        String lower = (href + " " + title + " " + snippet).toLowerCase();
        for (String key : siteKeys) {
            String domain = SITE_OPTIONS.get(key);
            if (domain != null && lower.contains(domain)) return true;
        }
        return false;
    }
}
