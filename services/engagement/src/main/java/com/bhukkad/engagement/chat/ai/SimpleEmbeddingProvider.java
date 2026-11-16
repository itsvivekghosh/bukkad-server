package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Simple deterministic embedding provider for development and testing.
 * <p>
 * <b>DEV ONLY</b> — Uses SHA-256 hash of text to generate pseudo-embeddings.
 * Not semantically meaningful; only suitable for testing vector store functionality.
 * <p>
 * Activated on dev/test/local profiles. In production, replace with a real
 * embedding model (e.g., OpenAI text-embedding-3-small, Cohere, or local model).
 */
@Component
@Profile({"dev", "test", "local"})
public class SimpleEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 1536;
    private static final ThreadLocal<MessageDigest> DIGEST_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[DIMENSION];
        }

        // Create a deterministic but pseudo-random embedding from text hash
        MessageDigest digest = DIGEST_THREAD_LOCAL.get();
        digest.reset();
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));

        // Expand hash to full dimension using a simple PRNG
        float[] embedding = new float[DIMENSION];
        long seed = bytesToLong(hash);
        for (int i = 0; i < DIMENSION; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L) & 0xFFFFFFFFFFFFFFFFL;
            // Normalize to [-1, 1]
            embedding[i] = ((seed >>> 16) & 0xFFFF) / 32767.5f - 1.0f;
        }

        // L2 normalize
        normalize(embedding);
        return embedding;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    private static long bytesToLong(byte[] bytes) {
        long result = 0;
        for (int i = 0; i < Math.min(8, bytes.length); i++) {
            result = (result << 8) | (bytes[i] & 0xFF);
        }
        return result;
    }

    private static void normalize(float[] vector) {
        double sumSq = 0;
        for (float v : vector) {
            sumSq += v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}