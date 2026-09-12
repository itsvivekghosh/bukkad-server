package com.bhukkad.restaurant.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory trie for menu autocomplete (port of monolith {@code TrieIndex}).
 * Thread-safe for reads after {@link #insert} during warmup; single writer
 * expected.
 */
public class TrieIndex {

    private final Node root = new Node();

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        boolean terminal;
    }

    public void insert(String word) {
        if (word == null || word.isBlank()) return;
        Node node = root;
        for (char c : word.toLowerCase().toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new Node());
        }
        node.terminal = true;
    }

    public void insertAll(List<String> words) {
        if (words != null) words.forEach(this::insert);
    }

    public List<String> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) return List.of();
        Node node = root;
        for (char c : prefix.toLowerCase().toCharArray()) {
            node = node.children.get(c);
            if (node == null) return List.of();
        }
        List<String> results = new ArrayList<>();
        collect(node, new StringBuilder(prefix.toLowerCase()), results, limit);
        return results;
    }

    public boolean contains(String word) {
        if (word == null || word.isBlank()) return false;
        Node node = root;
        for (char c : word.toLowerCase().toCharArray()) {
            node = node.children.get(c);
            if (node == null) return false;
        }
        return node.terminal;
    }

    private void collect(Node node, StringBuilder sb, List<String> results, int limit) {
        if (results.size() >= limit) return;
        if (node.terminal) results.add(sb.toString());
        for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
            sb.append(entry.getKey());
            collect(entry.getValue(), sb, results, limit);
            sb.deleteCharAt(sb.length() - 1);
        }
    }
}