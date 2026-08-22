package com.bhukkad.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrieIndexTest {

    private TrieIndex trie;

    @BeforeEach
    void setUp() {
        trie = new TrieIndex();
    }

    @Test
    void prefixSearch_returnsMatchingEntries() {
        trie.rebuild(List.of(
                new TrieIndex.Entry("pizza palace", "Pizza Palace", 1, "RESTAURANT"),
                new TrieIndex.Entry("pasta hut", "Pasta Hut", 2, "RESTAURANT"),
                new TrieIndex.Entry("paneer tikka", "Paneer Tikka", 3, "MENU_ITEM")
        ));

        List<TrieIndex.Entry> results = trie.prefixSearch("pa", 10);

        assertEquals(2, results.size());
        assertEquals("paneer tikka", results.get(0).key());
        assertEquals("pasta hut", results.get(1).key());
    }

    @Test
    void prefixSearch_isCaseInsensitive() {
        trie.insert("Pizza Palace", 1, "RESTAURANT");

        assertEquals(1, trie.prefixSearch("PIZ", 5).size());
        assertEquals(1, trie.prefixSearch("piz", 5).size());
    }

    @Test
    void prefixSearch_emptyPrefix_returnsEmpty() {
        trie.insert("Pizza Palace", 1, "RESTAURANT");
        assertTrue(trie.prefixSearch("", 5).isEmpty());
        assertTrue(trie.prefixSearch(null, 5).isEmpty());
    }

    @Test
    void prefixSearch_noMatch_returnsEmpty() {
        trie.insert("Pizza Palace", 1, "RESTAURANT");
        assertTrue(trie.prefixSearch("sushi", 5).isEmpty());
    }

    @Test
    void prefixSearch_respectsLimit() {
        trie.rebuild(List.of(
                new TrieIndex.Entry("apply", "apply", 1, "R"),
                new TrieIndex.Entry("apple", "apple", 2, "R"),
                new TrieIndex.Entry("apricot", "apricot", 3, "R")
        ));

        assertEquals(2, trie.prefixSearch("ap", 2).size());
        assertEquals(3, trie.prefixSearch("ap", 10).size());
        assertEquals("apple", trie.prefixSearch("ap", 10).get(0).key());
    }

    @Test
    void rebuild_replacesPreviousIndex() {
        trie.insert("old name", 1, "RESTAURANT");
        trie.rebuild(List.of(new TrieIndex.Entry("new name", "new name", 2, "RESTAURANT")));

        assertTrue(trie.prefixSearch("old", 5).isEmpty());
        assertEquals(1, trie.prefixSearch("new", 5).size());
        assertEquals(1, trie.size());
    }

    @Test
    void size_countsAllEntries() {
        trie.rebuild(List.of(
                new TrieIndex.Entry("a", "a", 1, "R"),
                new TrieIndex.Entry("ab", "ab", 2, "R"),
                new TrieIndex.Entry("ab", "ab", 3, "R")
        ));
        assertEquals(3, trie.size());
    }
}
