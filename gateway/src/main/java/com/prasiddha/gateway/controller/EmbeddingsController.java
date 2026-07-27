package com.prasiddha.gateway.controller;

import com.prasiddha.gateway.model.request.EmbeddingsRequest;
import com.prasiddha.gateway.service.EmbeddingClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeddings endpoint — turns text into vectors using the gateway's already-configured
 * embeddings provider (the same one F1/F2 use), so authenticated callers get embeddings
 * without holding an embeddings key themselves. Powers client-side RAG in Sahayatri.
 *
 * Auth: falls under SecurityConfig's {@code anyRequest().authenticated()} — a JWT or an
 * X-API-Key both satisfy it (unlike /chat, which specifically requires an API key).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Embeddings")
public class EmbeddingsController {

    private final EmbeddingClient embeddingClient;

    @PostMapping("/embeddings")
    @Operation(
        summary = "Embed one or more texts with the gateway's configured embeddings model",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<Map<String, Object>> embeddings(@Valid @RequestBody EmbeddingsRequest request) {
        if (!embeddingClient.isConfigured()) {
            return ResponseEntity.status(503)
                .body(Map.of("error", "Embeddings are not configured on this gateway."));
        }
        List<float[]> vectors = new ArrayList<>(request.getInputs().size());
        for (String input : request.getInputs()) {
            vectors.add(embeddingClient.embed(input));
        }
        int dim = vectors.isEmpty() ? 0 : vectors.get(0).length;
        return ResponseEntity.ok(Map.of("vectors", vectors, "dim", dim));
    }
}
