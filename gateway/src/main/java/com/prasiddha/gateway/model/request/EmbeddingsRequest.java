package com.prasiddha.gateway.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * What clients POST to /api/v1/embeddings.
 *
 * {
 *   "inputs": ["chunk one", "chunk two", ...]
 * }
 *
 * Each input is embedded with the gateway's configured embeddings model
 * (app.llm.embeddings.*), so callers need no embeddings key of their own. Bounded
 * (≤64 inputs, ≤8000 chars each) to keep a single request cheap and predictable.
 */
@Data
public class EmbeddingsRequest {

    @NotEmpty(message = "inputs must not be empty")
    @Size(max = 64, message = "at most 64 inputs per request")
    private List<@NotBlank @Size(max = 8000, message = "each input must not exceed 8000 characters") String> inputs;
}
