package com.bhukkad.restaurant.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrieIndexTest {

    private final TrieIndex trie = new TrieIndex();

    private TrieIndex seeded() {
        trie.insertAll(List.of("paneer", "paneer tikka", "pizza", "pasta", "palak paneer"));
        return trie;
    }

    @Test
    void autocomplete_returnsMatchingPrefix() {
        var results = seeded().autocomplete("pan", 10);
        assertThat(results).contains("paneer", "paneer tikka");
    }

    @Test
    void autocomplete_emptyPrefix_returnsEmpty() {
        assertThat(seeded().autocomplete("", 10)).isEmpty();
    }

    @Test
    void autocomplete_noMatch_returnsEmpty() {
        assertThat(seeded().autocomplete("xyz", 10)).isEmpty();
    }

    @Test
    void autocomplete_respectsLimit() {
        var results = seeded().autocomplete("p", 2);
        assertThat(results).hasSize(2);
    }

    @Test
    void autocomplete_caseInsensitive() {
        assertThat(seeded().autocomplete("PAN", 10)).contains("paneer");
    }

    @Test
    void contains_exactMatch() {
        assertThat(seeded().contains("pizza")).isTrue();
        assertThat(seeded().contains("burger")).isFalse();
    }

    @Test
    void insert_nullOrBlank_ignored() {
        trie.insert(null);
        trie.insert("   ");
        assertThat(trie.contains(null)).isFalse();
    }
}
