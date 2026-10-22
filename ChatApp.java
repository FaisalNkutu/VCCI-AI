package com.softknf.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.http.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;

public final class ChatApp extends JFrame {
    private static final String AUTO = "Auto Select";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final JTextField service = new JTextField("http://127.0.0.1:9999", 25);
    private final JComboBox<String> agents = new JComboBox<>();
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(4, 60);
    private final JTextArea userContext = new JTextArea(3, 40);
    private final JLabel status = new JLabel("Ready");
    private final JButton send = new JButton("Send");
    private final JButton refresh = new JButton("Refresh Agents");
    private final JButton reset = new JButton("New Chat");
    private final Map<String, JsonNode> registry = new LinkedHashMap<>();
    private final Map<String, JsonNode> pythonAgents = new LinkedHashMap<>();
    private final ParadigmAgentDiscovery paradigmDiscovery =
            new ParadigmAgentDiscovery(this::onParadigmAgents);
    private final List<ObjectNode> history = new ArrayList<>();
    private final String aiCoreRegistryUrl = System.getenv("AICORE_REGISTRY_URL");
    private String contextId = UUID.randomUUID().toString();
    private String previousAgentId;

    public ChatApp() {
        super("Standalone AI Chatbot — Python A2A Service");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(980, 760);
        setLocationRelativeTo(null);
        agents.addItem(AUTO);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Service:")); top.add(service);
        top.add(new JLabel("Agent:")); top.add(agents); top.add(refresh); top.add(reset);
        transcript.setEditable(false); transcript.setLineWrap(true); transcript.setWrapStyleWord(true);
        input.setLineWrap(true); input.setWrapStyleWord(true);
        userContext.setLineWrap(true); userContext.setWrapStyleWord(true);
        JPanel contextPanel = new JPanel(new BorderLayout());
        contextPanel.add(new JLabel("Shared user/task context (sent on each request):"), BorderLayout.NORTH);
        contextPanel.add(new JScrollPane(userContext), BorderLayout.CENTER);
        JPanel compose = new JPanel(new BorderLayout(8, 8));
        compose.add(contextPanel, BorderLayout.NORTH);
        compose.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.add(status, BorderLayout.CENTER); bottom.add(send, BorderLayout.EAST);
        compose.add(bottom, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH); add(new JScrollPane(transcript), BorderLayout.CENTER);
        add(compose, BorderLayout.SOUTH);
        send.addActionListener(e -> submit());
        refresh.addActionListener(e -> refreshAgents());
        reset.addActionListener(e -> {contextId=UUID.randomUUID().toString();previousAgentId=null;history.clear();transcript.setText("");status.setText("New conversation");});
        SwingUtilities.invokeLater(this::refreshAgents);
    }

    private String base() { return service.getText().trim().replaceAll("/+$", ""); }
    private JsonNode request(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(120));
        if ("GET".equals(method)) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()/100 != 2) throw new IllegalStateException("HTTP "+response.statusCode()+": "+response.body());
        return json.readTree(response.body());
    }
    private void background(String label, Job job) {
        send.setEnabled(false); refresh.setEnabled(false); status.setText(label);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { job.run(); return null; }
            @Override protected void done() {
                send.setEnabled(true); refresh.setEnabled(true);
                try { get(); status.setText("Ready"); }
                catch (Exception e) { Throwable cause=e.getCause()==null?e:e.getCause(); status.setText("Error: "+cause.getMessage()); append("ERROR", cause.toString()); }
            }
        }.execute();
    }
    private interface Job { void run() throws Exception; }
    private void refreshAgents() {
        background("Discovering agents...", () -> {
            JsonNode response=request("GET", "/agents", null);
            Map<String,JsonNode> found=new LinkedHashMap<>();
            for (JsonNode a:response.path("agents")) {
                String id=a.path("id").asText("");
                if (!id.isBlank()) found.put(a.path("name").asText(id)+" ["+id+"]", a);
            }
            SwingUtilities.invokeLater(() -> {
                pythonAgents.clear(); pythonAgents.putAll(found);
                rebuildAgentDropdown();
            });
            if (aiCoreRegistryUrl != null && !aiCoreRegistryUrl.isBlank()) {
                try {
                    AICoreHttpDiscovery.Result core = new AICoreHttpDiscovery(aiCoreRegistryUrl).discover();
                    acceptParadigmAgents(core.commands(), core.urls());
                } catch (Exception ex) {
                    append("AI Core discovery", ex.getMessage());
                }
            }
        });
    }
    /** Receives the asynchronous AIService.LIST result without replacing Python agents. */
    public void acceptParadigmAgents(Map<String, ? extends List<String>> commands,
                                     Map<String, String> agentURLs) {
        paradigmDiscovery.acceptResponse(commands, agentURLs);
    }

    private void onParadigmAgents(List<ParadigmAgentDiscovery.Agent> discovered) {
        SwingUtilities.invokeLater(this::rebuildAgentDropdown);
    }

    private void rebuildAgentDropdown() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::rebuildAgentDropdown);
            return;
        }
        String selection = (String) agents.getSelectedItem();
        registry.clear();
        registry.putAll(pythonAgents);
        for (ParadigmAgentDiscovery.Agent agent : paradigmDiscovery.snapshot()) {
            String commandId = agent.commandId();
            String friendlyName = agent.name();
            String label = friendlyName + " [AI Core]";

            ObjectNode info = json.createObjectNode();
            info.put("id", commandId);
            info.put("commandId", commandId);
            info.put("name", friendlyName);
            info.put("type", "aicore");
            info.put("endpoint", agent.endpoint());

            if (registry.containsKey(label)) {
                label = friendlyName + " (" + commandId + ") [AI Core]";
            }
            registry.put(label, info);
        }
        agents.removeAllItems();
        agents.addItem(AUTO);
        registry.keySet().forEach(agents::addItem);
        if (selection != null) agents.setSelectedItem(selection);
        status.setText("Python: " + pythonAgents.size() + " | AI Core: "
                + paradigmDiscovery.snapshot().size());
    }

    private ObjectNode context() {
        ObjectNode c=json.createObjectNode(); c.put("userContext",userContext.getText());
        c.put("taskContext",""); c.put("systemInstructions","");
        ArrayNode h=c.putArray("conversationHistory");
        synchronized(history) { history.stream().skip(Math.max(0,history.size()-12)).forEach(h::add); }
        c.putArray("agentResults"); return c;
    }
    private void submit() {
        String prompt=input.getText().trim(); if (prompt.isEmpty()) return;
        String choice=(String)agents.getSelectedItem();
        Map<String,JsonNode> snapshot=new LinkedHashMap<>(registry);
        String currentContext=contextId;
        ObjectNode shared=context();
        input.setText(""); append("YOU",prompt);
        background("Selecting and contacting agent...", () -> {
            JsonNode selected;
            if (AUTO.equals(choice)) {
                ObjectNode r=json.createObjectNode(); r.put("message",prompt);
                r.put("contextId",currentContext); if(previousAgentId!=null)r.put("previousAgentId",previousAgentId);
                r.set("context",shared); ArrayNode a=r.putArray("agents");
                snapshot.values().stream()
                        .filter(agent -> !"aicore".equals(agent.path("type").asText()))
                        .forEach(a::add);
                JsonNode routed=request("POST","/router/select",r);
                selected=routed.path("agent");
                if(selected.isMissingNode()||selected.isNull()) throw new IllegalStateException("No matching agent: "+routed.path("reason").asText());
            } else selected=snapshot.get(choice);
            if(selected==null)throw new IllegalStateException("Select an agent or refresh discovery");
            String id=selected.path("id").asText();
            // The uploaded Python A2A executor supports North agents only.
            if ("aicore".equalsIgnoreCase(selected.path("type").asText())) {
                String endpoint = selected.path("endpoint").asText("");
                if (endpoint.isBlank()) throw new IllegalStateException("AI Core agent has no endpoint: " + id);
                String coreAnswer = sendAICore(endpoint, prompt, shared);
                previousAgentId = id;
                synchronized (history) {
                    history.add(turn("user", prompt, null));
                    history.add(turn("assistant", coreAnswer, id));
                }
                SwingUtilities.invokeLater(() -> append(selected.path("name").asText(id), coreAnswer));
                return;
            }
            if (!"north".equalsIgnoreCase(selected.path("type").asText("north")))
                throw new IllegalStateException("Unsupported agent type: " + selected.path("type").asText());
            ObjectNode rpc=json.createObjectNode();rpc.put("jsonrpc","2.0");rpc.put("id",UUID.randomUUID().toString());rpc.put("method","SendStreamingMessage");
            ObjectNode params=rpc.putObject("params");ObjectNode message=params.putObject("message");
            message.put("messageId",UUID.randomUUID().toString());message.put("role","ROLE_USER");
            message.put("contextId",currentContext);
            ArrayNode parts=message.putArray("parts");parts.addObject().put("text",prompt);
            ObjectNode metadata=message.putObject("metadata");metadata.put("agent_id",id);metadata.set("context",shared);
            String answer=sendA2A(rpc);
            if(answer.isBlank()) answer="Agent returned no text response.";
            final String finalAnswer=answer;
            previousAgentId=id;
            synchronized(history) { history.add(turn("user",prompt,null));history.add(turn("assistant",finalAnswer,id)); }
            String displayName=selected.path("name").asText(id);
            SwingUtilities.invokeLater(() -> append(displayName,finalAnswer));
        });
    }

    /** Direct HTTP execution for AI Core agents exposing a JSON POST endpoint. */
    private String sendAICore(String endpoint, String prompt, ObjectNode shared) throws Exception {
        URI target = URI.create(endpoint);
        if (!List.of("http", "https").contains(target.getScheme()))
            throw new IllegalStateException("Unsupported AI Core endpoint scheme: " + endpoint);
        ObjectNode body = json.createObjectNode();
        body.put("message", prompt);
        body.put("contextId", contextId);
        body.set("context", shared);
        HttpRequest req = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2)
            throw new IllegalStateException("AI Core endpoint HTTP " + resp.statusCode() + ": " + resp.body());
        JsonNode result = json.readTree(resp.body());
        if (result.hasNonNull("error")) throw new IllegalStateException(result.path("error").toString());
        for (String field : List.of("answer", "response", "text", "message")) {
            JsonNode value = result.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        String extracted = extractText(result.path("result").isMissingNode() ? result : result.path("result"));
        return extracted.isBlank() ? result.toPrettyString() : extracted;
    }

    /** Stream A2A JSON-RPC events from the Python service. */
    private String sendA2A(ObjectNode rpc) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(rpc)))
                .build();
        HttpResponse<InputStream> response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            try (InputStream in = response.body()) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                        + new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        StringBuilder answer = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        StringBuilder fallback = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    eventData.append(line.substring(5).stripLeading());
                } else if (line.isEmpty()) {
                    if (eventData.length() > 0) {
                        consumeEvent(eventData.toString(), answer, fallback);
                        eventData.setLength(0);
                    }
                } else if (!line.startsWith(":")) {
                    // Also handle non-streaming JSON response bodies.
                    if (!line.startsWith("event:") && !line.startsWith("id:") && !line.startsWith("retry:"))
                        fallback.append(line);
                }
            }
            if (eventData.length() > 0) consumeEvent(eventData.toString(), answer, fallback);
        }
        if (answer.length() == 0 && fallback.length() > 0) {
            consumeEvent(fallback.toString(), answer, new StringBuilder());
        }
        return answer.toString().trim();
    }

    private void consumeEvent(String data, StringBuilder answer, StringBuilder unused) throws Exception {
        if (data.isBlank() || "[DONE]".equals(data)) return;
        JsonNode event = json.readTree(data);
        if (event.hasNonNull("error"))
            throw new IllegalStateException(event.path("error").toString());
        JsonNode result = event.has("result") ? event.path("result") : event;
        // A2A v1.0 wraps streamed chunks under result.artifactUpdate.artifact.
        JsonNode update = result.path("artifactUpdate");
        if (!update.isMissingNode()) {
            StringBuilder chunk = new StringBuilder();
            for (JsonNode part : update.path("artifact").path("parts")) {
                if (part.hasNonNull("text")) chunk.append(part.path("text").asText());
            }
            if (!update.path("append").asBoolean(false)) answer.setLength(0);
            answer.append(chunk);
            return;
        }
        // A2A status updates and task-submitted events are not answer text.
        if (result.has("statusUpdate") || result.has("task")) return;
        String text = extractText(result);
        if (!text.isBlank() && answer.length() == 0) answer.append(text);
    }

    private ObjectNode turn(String role,String content,String agentId) {
        ObjectNode t=json.createObjectNode();t.put("role",role);t.put("content",content);
        if(agentId!=null)t.put("agentId",agentId);return t;
    }
    private String extractText(JsonNode result) {
        StringBuilder out=new StringBuilder();
        collectParts(result.path("parts"),out);
        for(JsonNode artifact:result.path("artifacts")) collectParts(artifact.path("parts"),out);
        collectParts(result.path("status").path("message").path("parts"),out);
        return out.toString().trim();
    }
    private void collectParts(JsonNode parts,StringBuilder out) {
        if(!parts.isArray())return;
        for(JsonNode p:parts) {
            String value=p.path("text").asText("");
            if(value.isBlank())value=p.path("root").path("text").asText("");
            if(!value.isBlank())out.append(value).append('\n');
        }
    }
    private void append(String who,String text) {
        if(!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(()->append(who,text));return; }
        transcript.append(who+":\n"+text+"\n\n");transcript.setCaretPosition(transcript.getDocument().getLength());
    }
    public static void main(String[] args) { SwingUtilities.invokeLater(()->new ChatApp().setVisible(true)); }
}