package com.acme.reliable.web;

import com.acme.reliable.core.CommandBus;
import com.acme.reliable.core.ResponseRegistry;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Controller("/commands")
public class CommandController {
    private final CommandBus bus;
    private final ResponseRegistry responses;

    public CommandController(CommandBus b, ResponseRegistry r) {
        this.bus = b;
        this.responses = r;
    }

    @Post("/{name}")
    public HttpResponse<?> submit(
            @PathVariable String name,
            @Header("Idempotency-Key") String idem,
            @Body String payload,
            @Header(value = "Reply-To", defaultValue = "APP.CMD.REPLY.Q") String replyTo) {

        var cmdId = bus.accept(name, idem, businessKey(payload), payload,
                java.util.Map.of("mode", "mq", "replyTo", replyTo));

        // Register for response and wait up to 1 second
        var future = responses.register(cmdId);

        try {
            String response = future.get(1, TimeUnit.SECONDS);
            return HttpResponse.ok(response)
                    .header("X-Command-Id", cmdId.toString())
                    .header("X-Correlation-Id", cmdId.toString());
        } catch (TimeoutException e) {
            // Timeout - return accepted status
            return HttpResponse.accepted()
                    .header("X-Command-Id", cmdId.toString())
                    .header("X-Correlation-Id", cmdId.toString())
                    .body("{\"message\":\"Command accepted, processing asynchronously\"}");
        } catch (Exception e) {
            // Error during processing
            return HttpResponse.serverError()
                    .header("X-Command-Id", cmdId.toString())
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private String businessKey(String payload) {
        // Simple key derivation - in production, extract from payload
        // For now, use a UUID to ensure uniqueness
        return java.util.UUID.randomUUID().toString();
    }
}
