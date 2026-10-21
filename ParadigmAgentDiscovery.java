package com.softknf.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-safe boundary between an asynchronous Paradigm LIST response
 * and the standalone Swing chatbot. Does not initialize VCCI UI components.
 */
public final class ParadigmAgentDiscovery {
    public record Agent(String name, String endpoint, List<String> commands) {
        public Agent {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(endpoint, "endpoint");
            commands = List.copyOf(commands == null ? List.of() : commands);
        }
    }

    private final Consumer<List<Agent>> onUpdate;
    private List<Agent> snapshot = List.of();

    public ParadigmAgentDiscovery(Consumer<List<Agent>> onUpdate) {
        this.onUpdate = Objects.requireNonNull(onUpdate, "onUpdate");
    }

    /** Call this from the successful AISGetCommandsRequest processResponse branch. */
    public void acceptResponse(Map<String, ? extends List<String>> commandList,
                               Map<String, String> agentURLs) {
        Map<String, ? extends List<String>> commands =
                commandList == null ? Collections.emptyMap() : commandList;
        Map<String, String> urls =
                agentURLs == null ? Collections.emptyMap() : agentURLs;

        // Preserve all returned agent names, even when an endpoint is missing.
        Map<String, Agent> discovered = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<String>> entry : commands.entrySet()) {
            String name = entry.getKey();
            if (name != null && !name.isBlank()) {
                discovered.put(name, new Agent(name, urls.getOrDefault(name, ""), entry.getValue()));
            }
        }
        for (Map.Entry<String, String> entry : urls.entrySet()) {
            String name = entry.getKey();
            if (name != null && !name.isBlank()) {
                discovered.putIfAbsent(name, new Agent(name,
                        entry.getValue() == null ? "" : entry.getValue(), List.of()));
            }
        }
        List<Agent> fresh = List.copyOf(new ArrayList<>(discovered.values()));
        synchronized (this) {
            snapshot = fresh; // Replaces stale name AND URL cache atomically.
        }
        onUpdate.accept(fresh);
    }

    public synchronized List<Agent> snapshot() {
        return snapshot;
    }
}
