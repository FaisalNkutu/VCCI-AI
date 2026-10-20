package com.softknf.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.http.*;
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
    private final List<ObjectNode> history = new ArrayList<>();
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
                registry.clear(); registry.putAll(found); agents.removeAllItems(); agents.addItem(AUTO);
                registry.keySet().forEach(agents::addItem);
                status.setText("Discovered "+found.size()+" agent(s)");
            });
        });
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
                r.set("context",shared); ArrayNode a=r.putArray("agents"); snapshot.values().forEach(a::add);
                JsonNode routed=request("POST","/router/select",r);
                selected=routed.path("agent");
                if(selected.isMissingNode()||selected.isNull()) throw new IllegalStateException("No matching agent: "+routed.path("reason").asText());
            } else selected=snapshot.get(choice);
            if(selected==null)throw new IllegalStateException("Select an agent or refresh discovery");
            String id=selected.path("id").asText();
            // The uploaded Python A2A executor supports North agents only.
            if (!"north".equalsIgnoreCase(selected.path("type").asText("north")))
                throw new IllegalStateException("Non-North agents require their own endpoint adapter");
            ObjectNode rpc=json.createObjectNode();rpc.put("jsonrpc","2.0");rpc.put("id",UUID.randomUUID().toString());rpc.put("method","message/send");
            ObjectNode params=rpc.putObject("params");ObjectNode message=params.putObject("message");
            message.put("messageId",UUID.randomUUID().toString());message.put("role","user");
            message.put("contextId",currentContext);
            ArrayNode parts=message.putArray("parts");parts.addObject().put("kind","text").put("text",prompt);
            ObjectNode metadata=message.putObject("metadata");metadata.put("agent_id",id);metadata.set("context",shared);
            JsonNode response=request("POST","/",rpc);
            if(response.hasNonNull("error"))throw new IllegalStateException(response.path("error").toString());
            JsonNode result=response.path("result");
            String answer=extractText(result);
            if(answer.isBlank()) answer="Request completed, but no text response was returned. Raw result: "+result;
            final String finalAnswer=answer;
            previousAgentId=id;
            synchronized(history) { history.add(turn("user",prompt,null));history.add(turn("assistant",finalAnswer,id)); }
            String displayName=selected.path("name").asText(id);
            SwingUtilities.invokeLater(() -> append(displayName,finalAnswer));
        });
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
