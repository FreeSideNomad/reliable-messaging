package com.acme.reliable.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

final class HttpClientSupport {
    private HttpClientSupport() {
    }

    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    static URI baseUri(int port) {
        return URI.create("http://localhost:" + port);
    }
}
