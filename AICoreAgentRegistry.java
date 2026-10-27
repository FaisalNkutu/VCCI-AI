package com.softknf.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe storage for agent URLs returned asynchronously by Paradigm. */
public final class AICoreAgentRegistry {
    private final Map<String, String> agentURLs = new LinkedHashMap<>();

    public synchronized void replaceAgents(Map<String, String> discoveredURLs) {
        agentURLs.clear();
        if (discoveredURLs == null) return;
        discoveredURLs.forEach((name, url) -> {
            if (name != null && !name.isBlank() && url != null && !url.isBlank()) {
                agentURLs.put(name, url);
            }
        });
    }

    public synchronized Map<String, String> getAgentURLs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(agentURLs));
    }

    public synchronized int size() { return agentURLs.size(); }
}
