package com.acme.reliable.web;

import com.acme.reliable.core.CommandBus;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest(transactional = false)
class CommandControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    CommandBus commandBus;

    @MockBean(CommandBus.class)
    CommandBus mockCommandBus() {
        return mock(CommandBus.class);
    }

    @Test
    void testSubmitCommand() {
        UUID commandId = UUID.randomUUID();
        when(commandBus.accept(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(commandId);

        var request = HttpRequest.POST("/commands/CreateUser", "{\"username\":\"alice\"}")
                .header("Idempotency-Key", "test-idem-123")
                .header("Reply-To", "MY.REPLY.Q");

        var response = client.toBlocking().exchange(request, String.class);

        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
        assertNotNull(response.getHeaders().get("X-Command-Id"));
        assertEquals(commandId.toString(), response.getHeaders().get("X-Command-Id"));
        assertNotNull(response.getHeaders().get("X-Correlation-Id"));
        assertEquals(commandId.toString(), response.getHeaders().get("X-Correlation-Id"));

        verify(commandBus).accept(
                eq("CreateUser"),
                eq("test-idem-123"),
                anyString(), // Business key is now randomly generated
                eq("{\"username\":\"alice\"}"),
                eq(Map.of("mode", "mq", "replyTo", "MY.REPLY.Q"))
        );
    }

    @Test
    void testSubmitCommandWithDefaultReplyTo() {
        UUID commandId = UUID.randomUUID();
        when(commandBus.accept(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(commandId);

        var request = HttpRequest.POST("/commands/TestCommand", "{}")
                .header("Idempotency-Key", "test-idem-456");

        var response = client.toBlocking().exchange(request, String.class);

        assertEquals(HttpStatus.ACCEPTED, response.getStatus());

        verify(commandBus).accept(
                eq("TestCommand"),
                eq("test-idem-456"),
                anyString(), // Business key is now randomly generated
                eq("{}"),
                eq(Map.of("mode", "mq", "replyTo", "APP.CMD.REPLY.Q"))
        );
    }

    @Test
    void testSubmitCommandDuplicateIdempotencyKey() {
        when(commandBus.accept(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("Duplicate idempotency key"));

        var request = HttpRequest.POST("/commands/CreateUser", "{}")
                .header("Idempotency-Key", "duplicate-key");

        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, String.class);
        });

        verify(commandBus).accept(
                eq("CreateUser"),
                eq("duplicate-key"),
                anyString(), // Business key is now randomly generated
                eq("{}"),
                anyMap()
        );
    }

    @Test
    void testSubmitCommandMissingIdempotencyKey() {
        var request = HttpRequest.POST("/commands/CreateUser", "{}");

        // Should fail because Idempotency-Key header is required
        assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, String.class);
        });

        verifyNoInteractions(commandBus);
    }
}
