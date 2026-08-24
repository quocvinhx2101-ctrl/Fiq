package io.fiq.server.api;

import java.net.URI;
import java.time.Instant;

public record ApiProblem(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        Instant timestamp,
        String code) {}
