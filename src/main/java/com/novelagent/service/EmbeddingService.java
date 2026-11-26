package com.novelagent.service;

import com.novelagent.config.EmbeddingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.security.cert.X509Certificate;
import java.util.*;

@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingConfig config;
    private final DynamicConfigService configService;
    private final RestTemplate restTemplate;

    /** 创建跳过 SSL 证书验证的 RestTemplate（开发环境，生产要删掉） */
    private static RestTemplate createLenientRestTemplate() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            log.error("Failed to configure lenient SSL", e);
        }
        return new RestTemplate();
    }

    public EmbeddingService(EmbeddingConfig config, DynamicConfigService configService) {
        this.config = config;
        this.configService = configService;
        this.restTemplate = createLenientRestTemplate();
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text))[0];
    }

    /**
     * 批量 embedding — 自动分片，每批最多 10 条（Qwen API 限制）。
     */
    public float[][] embedBatch(List<String> texts) {
        int batchSize = 10;
        float[][] allResults = new float[texts.size()][];
        int batches = (int) Math.ceil((double) texts.size() / batchSize);

        log.info("Embedding {} texts in {} batches (max {} per batch)", texts.size(), batches, batchSize);

        for (int b = 0; b < batches; b++) {
            int from = b * batchSize;
            int to = Math.min(from + batchSize, texts.size());
            List<String> slice = texts.subList(from, to);

            float[][] batchResult = embedSingleBatch(slice);
            System.arraycopy(batchResult, 0, allResults, from, batchResult.length);

            log.info("  Batch {}/{}: {} texts embedded", b + 1, batches, batchResult.length);
        }

        log.info("Embedded {} texts → {} dimensions", allResults.length,
                allResults[0] != null ? allResults[0].length : 0);
        return allResults;
    }

    /** 单次 API 调用，不超过 10 条 */
    private float[][] embedSingleBatch(List<String> texts) {
        String url = buildUrl();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configService.get("embed.model", config.getModel()));
        body.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKey = configService.get("embed.api_key", config.getApiKey());
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> respBody = resp.getBody();
            if (respBody == null) throw new RuntimeException("Embedding API returned empty");

            List<Map<String, Object>> data = (List<Map<String, Object>>) respBody.get("data");
            if (data == null) throw new RuntimeException("No embedding data: " + respBody);

            data.sort(Comparator.comparingInt(d -> (int) d.get("index")));

            float[][] result = new float[data.size()][];
            for (Map<String, Object> item : data) {
                int idx = (int) item.get("index");
                List<Double> emb = (List<Double>) item.get("embedding");
                if (emb == null) throw new RuntimeException("Missing embedding at index " + idx);
                result[idx] = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) result[idx][i] = emb.get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            log.error("Embedding API call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding failed: " + e.getMessage(), e);
        }
    }

    private String buildUrl() {
        String u = configService.get("embed.base_url", config.getBaseUrl());
        if (!u.endsWith("/")) u += "/";
        return u + "embeddings";
    }
}
