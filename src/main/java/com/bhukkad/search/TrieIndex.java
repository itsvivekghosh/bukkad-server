package com.bhukkad.search;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory prefix (trie) index used for typeahead/autocomplete over
 * restaurant and menu-item names. Built lazily from the source of truth
 * (DB) and kept behind a read-write lock so concurrent readers never see
 * a partially-rebuilt index.
 *
 * <p>Prefix search returns up to {@code limit} entries in lexicographic
 * order. The index is intentionally case-insensitive: names are stored
 * lowercased and the original display name is retained for output.
 */
@Component
public class TrieIndex {

    private final Node root = new Node();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile int size = 0;

    /** A single indexed term: normalized key plus display name and type. */
    public record Entry(String key, String displayName, long id, String type) {
    }

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        boolean terminal;
        List<Entry> entries = List.of();
    }

    /**
     * Replaces the whole index with the given entries. Callers typically
     * pass restaurant and menu-item names read from the DB.
     */
    public void rebuild(List<Entry> entries) {
        lock.writeLock().lock();
        try {
            root.children.clear();
            size = 0;
            for (Entry entry : entries) {
                insert(entry);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds a single entry. Prefer {@link #rebuild(List)} for bulk loads; this
     * is useful for incremental updates (e.g. after a new restaurant opens).
     */
    public void insert(String displayName, long id, String type) {
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        lock.writeLock().lock();
        try {
            insert(new Entry(displayName.trim().toLowerCase(), displayName.trim(), id, type));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void insert(Entry entry) {
        Node node = root;
        for (char c : entry.key().toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new Node());
        }
        if (!node.terminal) {
            node.terminal = true;
        }
        List<Entry> updated = new ArrayList<>(node.entries.size() + 1);
        updated.addAll(node.entries);
        updated.add(entry);
        node.entries = List.copyOf(updated);
        size++;
    }

    /**
     * Returns up to {@code limit} entries whose key starts with {@code prefix}
     * (case-insensitive), ordered lexicographically by key.
     */
    public List<Entry> prefixSearch(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        String normalized = prefix.trim().toLowerCase();
        lock.readLock().lock();
        try {
            Node node = root;
            for (char c : normalized.toCharArray()) {
                node = node.children.get(c);
                if (node == null) {
                    return List.of();
                }
            }
            List<Entry> results = new ArrayList<>(Math.min(limit, 16));
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(node);
            while (!stack.isEmpty() && results.size() < limit) {
                Node current = stack.pop();
                if (current.terminal) {
                    results.addAll(current.entries);
                }
                if (results.size() >= limit) {
                    break;
                }
                // Children pushed in reverse-lexicographic order so the pop
                // order (and therefore the result order) is lexicographic.
                List<Character> chars = new ArrayList<>(current.children.keySet());
                chars.sort(Character::compareTo);
                for (int i = chars.size() - 1; i >= 0; i--) {
                    stack.push(current.children.get(chars.get(i)));
                }
            }
            return List.copyOf(results);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Number of indexed entries (not distinct keys). */
    public int size() {
        return size;
    }
}
