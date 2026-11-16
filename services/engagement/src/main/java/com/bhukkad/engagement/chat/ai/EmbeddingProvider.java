package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Embedding provider abstraction for generating vector embeddings from text.
 */
public interface EmbeddingProvider {

    /**
     * Generates an embedding for the given text.
     *
     * @param text the input text
     * @return embedding vector (dimension depends on the model)
     */
    float[] embed(String text);

    /**
     * Returns the dimension of embeddings produced by this provider.
     */
    int dimension();
}