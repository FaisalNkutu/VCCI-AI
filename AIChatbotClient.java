/*
 * Copyright 2026 Operational and Training Technology, Calian Learning
 *                   All rights reserved.
 */

package com.simfront.vcci.ui.dashboard;
import com.calian.paradigm.service.aiservice.cohere.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import com.calian.common.lib.log.Log;
/**
 * VCCI AI Chatbot client.
 *
 * @author F. Nkutu
 * @company SimFront a Calian Company
 * Created 2026
 */
public class AIChatbotClient extends JDialog {
    private static final Logger jL = Log.getLogger(AIChatbotClient.class);
    private static final String AI_SERVICE_ADDRESS = "127.0.0.1:9999";
    private static final AgentInfo AUTO_SELECT_AGENT = new AgentInfo( "__AUTO__", "Auto Select", "Automatically selects the best AI agent " + "for the question.", "", false, "router" );
    private JComboBox<AgentInfo> agentComboBox;
    private JButton refreshAgentsButton;
    private JButton sendButton;
    private JButton newChatButton;
    private JButton closeButton;
    private JTextArea messageTextArea;
    private JTextArea responseTextArea;
    private JLabel agentDescriptionLabel;
    private JLabel statusLabel;
    private AgentInfo selectedAgent;
    private List<AgentInfo> availableAgents = new ArrayList<>();
    private String contextId;
    public AIChatbotClient( Frame owner) {
        super( owner, "AI Chatbot", true);
        contextId = UUID.randomUUID() .toString();
        initializeUI();
        loadAvailableAgents();
    }
    private void initializeUI() {
        setDefaultCloseOperation( DISPOSE_ON_CLOSE);
        setSize( 800, 650);
        setMinimumSize( new Dimension( 650, 500));
        JPanel root = new JPanel( new BorderLayout( 10, 10));
        root.setBorder( new EmptyBorder( 15, 15, 15, 15));
        root.add( createAgentPanel(), BorderLayout.NORTH);
        JPanel center = new JPanel( new GridLayout( 2, 1, 10, 10));
        messageTextArea = new JTextArea();
        messageTextArea.setLineWrap( true);
        messageTextArea.setWrapStyleWord( true);
        messageTextArea.setFont( new Font( Font.SANS_SERIF, Font.PLAIN, 14));
        messageTextArea.setMargin( new Insets( 8, 8, 8, 8));
        JPanel messagePanel = new JPanel( new BorderLayout());
        messagePanel.setBorder( BorderFactory .createTitledBorder( "Message"));
        messagePanel.add( new JScrollPane( messageTextArea), BorderLayout.CENTER);
        responseTextArea = new JTextArea();
        responseTextArea.setEditable( false);
        responseTextArea.setLineWrap( true);
        responseTextArea.setWrapStyleWord( true);
        responseTextArea.setFont( new Font( Font.SANS_SERIF, Font.PLAIN, 14));
        responseTextArea.setMargin( new Insets( 8, 8, 8, 8));
        JPanel responsePanel = new JPanel( new BorderLayout());
        responsePanel.setBorder( BorderFactory .createTitledBorder( "Response"));
        responsePanel.add( new JScrollPane( responseTextArea), BorderLayout.CENTER);
        center.add( messagePanel);
        center.add( responsePanel);
        root.add( center, BorderLayout.CENTER);
        JPanel bottomPanel = new JPanel( new BorderLayout());
        JPanel buttons = new JPanel( new FlowLayout( FlowLayout.RIGHT));
        newChatButton = new JButton( "New Chat");
        sendButton = new JButton( "Send");
        closeButton = new JButton( "Close");
        buttons.add( newChatButton);
        buttons.add( sendButton);
        buttons.add( closeButton);
        bottomPanel.add( buttons, BorderLayout.EAST);
        root.add( bottomPanel, BorderLayout.SOUTH);
        setContentPane( root);
        sendButton.addActionListener( event -> sendMessage());
        newChatButton.addActionListener( event -> newChat());
        closeButton.addActionListener( event -> dispose());
        setLocationRelativeTo( getOwner());
    }
    private JPanel createAgentPanel() {
        JPanel outer = new JPanel( new BorderLayout( 5, 5));
        JPanel selector = new JPanel( new BorderLayout( 10, 0));
        JLabel label = new JLabel( "Agent:");
        label.setFont( label.getFont() .deriveFont( Font.BOLD));
        agentComboBox = new JComboBox<>();
        refreshAgentsButton = new JButton( "Refresh Agents");
        selector.add( label, BorderLayout.WEST);
        selector.add( agentComboBox, BorderLayout.CENTER);
        selector.add( refreshAgentsButton, BorderLayout.EAST);
        JPanel information = new JPanel( new BorderLayout( 10, 0));
        agentDescriptionLabel = new JLabel( "Select an available AI agent.");
        statusLabel = new JLabel( "Not connected");
        information.add( agentDescriptionLabel, BorderLayout.CENTER);
        information.add( statusLabel, BorderLayout.EAST);
        outer.add( selector, BorderLayout.NORTH);
        outer.add( information, BorderLayout.SOUTH);
        refreshAgentsButton .addActionListener( event -> loadAvailableAgents());
        agentComboBox .addActionListener( event -> agentSelected());
        return outer;
    }
    private void loadAvailableAgents() {
        System.out.println("\n========================================");
        System.out.println("DEBUG: loadAvailableAgents() STARTED");
        System.out.println("========================================");
        selectedAgent = null;
        availableAgents = new ArrayList<>();
        agentComboBox.removeAllItems();
        agentComboBox.setEnabled(false);
        refreshAgentsButton.setEnabled(false);
        sendButton.setEnabled(false);
        statusLabel.setText("Loading agents...");
        agentDescriptionLabel.setText("Retrieving available agents...");
        SwingWorker<List<AgentInfo>, Void> worker = new SwingWorker<>() {
            @Override protected List<AgentInfo> doInBackground() throws Exception {
                System.out.println( "DEBUG: Calling AgentRegistryClient.getAgents()");
                List<AgentInfo> result = AgentRegistryClient.getAgents( AI_SERVICE_ADDRESS);
                System.out.println( "DEBUG: AgentRegistryClient returned: " + (result == null ? "NULL" : result.size() + " agents"));
                return result;
            }
            @Override protected void done() {
                try {
                    System.out.println( "\nDEBUG: SwingWorker.done() started");
                    List<AgentInfo> agents = get();
                    if (agents != null) {
                        availableAgents = new ArrayList<>(agents);
                        System.out.println( "DEBUG: Original discovered agents = " + availableAgents.size());
                        for (AgentInfo agent : availableAgents) {
                            System.out.println( "DEBUG: Original Agent -> " + agent.getName() + " | Endpoint = " + agent.getEndpoint());
                        }
                    }
                    else {
                        System.out.println( "DEBUG: AgentRegistryClient returned NULL");
                    }
                    HashMap<String, List<String>> aiCoreAgents = VCCIControlPanel.getAIServiceAgents();
                    HashMap<String, String> aiServiceAgentURLs = VCCIControlPanel.getAIServiceAgentURLs();
                    System.out.println( "\n========================================");
                    System.out.println( "DEBUG: ADDING AI CORE AGENTS");
                    System.out.println( "========================================");
                    System.out.println( "DEBUG: AI Core cached agent count = " + aiCoreAgents.size());
                    for (Map.Entry<String, List<String>> entry : aiCoreAgents.entrySet()) {
                        String agentName = entry.getKey();
                        List<String> commands = entry.getValue();
                        System.out.println( "DEBUG: AI Core Agent -> " + agentName + " | Commands = " + commands);
                        boolean alreadyExists = false;
                        for (AgentInfo existingAgent : availableAgents) {
                            if (existingAgent.getName() != null && existingAgent.getName() .equalsIgnoreCase( agentName)) {
                                alreadyExists = true;
                                break;
                            }
                        }
                        if (alreadyExists) {
                            System.out.println( "DEBUG: Agent already exists -> " + agentName);
                            continue;
                        }
                        String description = "AI Service Agent";
                        if (commands != null && !commands.isEmpty()) {
                            description = "Commands: " + String.join( ", ", commands);
                        }
                        AgentInfo aiCoreAgent = new AgentInfo( agentName, agentName, description, aiServiceAgentURLs.get(agentName), false, "AI_SERVICE");
                        availableAgents.add( aiCoreAgent);
                        System.out.println( "DEBUG: ADDED AI CORE AGENT -> " + agentName);
                        System.out.println( "DEBUG: ADDED AI CORE AGENT aiCoreAgent getDescription-> " + aiCoreAgent.getDescription());
                        System.out.println( "DEBUG: ADDED AI CORE AGENT aiCoreAgent getEndpoint-> " + aiCoreAgent.getEndpoint());
                        System.out.println( "DEBUG: ADDED AI CORE AGENT aiCoreAgent getType-> " + aiCoreAgent.getType());
                    }
                    System.out.println( "DEBUG: Combined agent count = " + availableAgents.size());
                    System.out.println( "========================================\n");
                    System.out.println( "\n========================================");
                    System.out.println( "DEBUG: FINAL availableAgents");
                    System.out.println( "========================================");
                    System.out.println( "DEBUG: Total availableAgents = " + availableAgents.size());
                    for (int i = 0; i < availableAgents.size(); i++) {
                        AgentInfo agent = availableAgents.get(i);
                        System.out.println( "DEBUG: availableAgents[" + i + "] = " + agent.getName() + " | endpoint = " + agent.getEndpoint());
                    }
                    if (availableAgents.isEmpty()) {
                        System.out.println( "DEBUG ERROR: availableAgents is EMPTY");
                        statusLabel.setText( "No agents available");
                        agentDescriptionLabel.setText( "No agents were returned.");
                        return;
                    }
                    System.out.println( "\n========================================");
                    System.out.println( "DEBUG: POPULATING COMBO BOX");
                    System.out.println( "========================================");
                    agentComboBox.addItem( AUTO_SELECT_AGENT);
                    System.out.println( "DEBUG: Added AUTO_SELECT_AGENT");
                    for (AgentInfo agent : availableAgents) {
                        System.out.println( "DEBUG: Adding to ComboBox -> " + agent.getName());
                        System.out.println( "DEBUG: getDescription -> " + agent.getDescription());
                        System.out.println( "DEBUG: getEndpoint -> " + agent.getEndpoint());
                        agentComboBox.addItem( agent);
                    }
                    System.out.println( "\n========================================");
                    System.out.println( "DEBUG: FINAL COMBO BOX CONTENT");
                    System.out.println( "========================================");
                    System.out.println( "DEBUG: ComboBox item count = " + agentComboBox.getItemCount());
                    for (int i = 0; i < agentComboBox.getItemCount(); i++) {
                        Object item = agentComboBox.getItemAt(i);
                        System.out.println( "DEBUG: ComboBox[" + i + "] = " + item);
                    }
                    agentComboBox.setSelectedIndex(0);
                    System.out.println( "DEBUG: Selected index = " + agentComboBox.getSelectedIndex());
                    System.out.println( "DEBUG: Selected item = " + agentComboBox.getSelectedItem());
                    agentSelected();
                    System.out.println( "\n========================================");
                    System.out.println( "DEBUG: loadAvailableAgents() SUCCESS");
                    System.out.println( "========================================\n");
                }
                catch (Exception exception) {
                    System.out.println( "\nDEBUG ERROR: Agent discovery failed");
                    System.out.println( "DEBUG ERROR TYPE: " + exception.getClass().getName());
                    System.out.println( "DEBUG ERROR MESSAGE: " + exception.getMessage());
                    exception.printStackTrace();
                    statusLabel.setText( "Agent discovery failed");
                    agentDescriptionLabel.setText( "Unable to retrieve agents.");
                    JOptionPane.showMessageDialog( AIChatbotClient.this, getRootMessage(exception), "Agent Discovery Error", JOptionPane.ERROR_MESSAGE);
                }
                finally {
                    refreshAgentsButton.setEnabled(true);
                    agentComboBox.setEnabled( agentComboBox.getItemCount() > 0);
                    sendButton.setEnabled( selectedAgent != null);
                    System.out.println( "DEBUG FINAL: ComboBox enabled = " + agentComboBox.isEnabled());
                    System.out.println( "DEBUG FINAL: ComboBox item count = " + agentComboBox.getItemCount());
                    System.out.println( "DEBUG FINAL: selectedAgent = " + selectedAgent);
                }
            }
        }
        ;
        worker.execute();
    }
    private void agentSelected() {
        selectedAgent = (AgentInfo) agentComboBox .getSelectedItem();
        if (selectedAgent == null) {
            agentDescriptionLabel .setText( "Select an available AI agent.");
            statusLabel .setText( "No agent selected");
            sendButton .setEnabled( false);
            return;
        }
        if (isAutoSelect( selectedAgent)) {
            agentDescriptionLabel .setText( "Automatically selects the best " + "AI agent for your question.");
            statusLabel .setText( "Auto Select Ready");
            sendButton .setEnabled( !availableAgents.isEmpty());
            System.out.println();
            System.out.println( "Agent Selection Mode");
            System.out.println( "Mode : Auto Select");
            return;
        }
        String description = selectedAgent .getDescription();
        if (description == null || description.isBlank()) {
            description = "A2A AI Agent";
        }
        agentDescriptionLabel .setText( description);
        statusLabel .setText( "Ready");
        sendButton .setEnabled( true);
        System.out.println();
        System.out.println( "Selected AI Agent");
        System.out.println( "Name : " + selectedAgent .getName());
        System.out.println( "ID : " + selectedAgent .getId());
        System.out.println( "Endpoint : " + selectedAgent .getEndpoint());
    }
    private void sendMessage() {
        if (selectedAgent == null) {
            JOptionPane .showMessageDialog( this, "Please select an agent.", "No Agent Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String message = messageTextArea .getText() .trim();
        if (message.isEmpty()) {
            JOptionPane .showMessageDialog( this, "Please enter a message.", "Empty Message", JOptionPane.WARNING_MESSAGE);
            messageTextArea .requestFocusInWindow();
            return;
        }
        final AgentInfo requestAgent;
        if (isAutoSelect( selectedAgent)) {
            requestAgent = autoSelectAgent( message);
            if (requestAgent == null) {
                JOptionPane .showMessageDialog( this, "No suitable AI agent " + "is currently available.", "Agent Selection", JOptionPane.WARNING_MESSAGE);
                statusLabel .setText( "No suitable agent");
                return;
            }
            statusLabel .setText( "Auto selected: " + requestAgent .getName());
            System.out.println();
            System.out.println( "Automatic Agent Selection");
            System.out.println( "Question : " + message);
            System.out.println( "Agent : " + requestAgent .getName());
            System.out.println( "ID : " + requestAgent .getId());
            System.out.println( "Endpoint : " + requestAgent .getEndpoint());
        }
        else {
            requestAgent = selectedAgent;
        }
        sendButton .setEnabled( false);
        agentComboBox .setEnabled( false);
        refreshAgentsButton .setEnabled( false);
        newChatButton .setEnabled( false);
        responseTextArea .setText( "Sending to " + requestAgent .getName() + "...");
        if (!isAutoSelect( selectedAgent)) {
            statusLabel .setText( "Processing...");
        }
        SwingWorker< String, Void> worker = new SwingWorker<>() {
            @Override protected String doInBackground() throws Exception {
                jL.debug("DEBUG SEND: agent name =" + requestAgent.getName());
                jL.debug("DEBUG SEND: agent id =" + requestAgent.getId());
                jL.debug("DEBUG SEND: agent endpoint =" + requestAgent.getEndpoint());
                jL.debug("DEBUG SEND: agent type =" + requestAgent.getType());
                return SendMessageClient .sendMessage( requestAgent, message, contextId);
            }
            @Override protected void done() {
                try {
                    String response = get();
                    if (response == null || response.isBlank()) {
                        response = "No response was returned " + "by the agent.";
                    }
                    responseTextArea .setText( response);
                    responseTextArea .setCaretPosition( 0);
                    if (isAutoSelect( selectedAgent)) {
                        statusLabel .setText( "Auto selected: " + requestAgent .getName());
                    }
                    else {
                        statusLabel .setText( "Response from " + requestAgent .getName());
                    }
                }
                catch (Exception exception) {
                    responseTextArea .setText( "Request failed:\n\n" + getRootMessage( exception));
                    responseTextArea .setCaretPosition( 0);
                    statusLabel .setText( "Request failed");
                    exception .printStackTrace();
                }
                finally {
                    sendButton .setEnabled( true);
                    agentComboBox .setEnabled( true);
                    refreshAgentsButton .setEnabled( true);
                    newChatButton .setEnabled( true);
                }
            }
        }
        ;
        worker.execute();
    }
    private AgentInfo autoSelectAgent( String question) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return null;
        }
        try {
            System.out.println();
            System.out.println( "========================================");
            System.out.println( "DEBUG AUTO: Calling Python /router/select");
            System.out.println( "DEBUG AUTO: Question = " + question);
            System.out.println( "DEBUG AUTO: Available agents = " + availableAgents.size());
            AgentRouterClient routerClient = new AgentRouterClient();
            AgentRoutingResult result = routerClient.route( question, contextId, null, availableAgents);
            if (result == null) {
                System.out.println( "DEBUG AUTO: Router returned NULL");
                return null;
            }
            AgentInfo routedAgent = result.getAgent();
            if (routedAgent == null) {
                System.out.println( "DEBUG AUTO: Router returned no agent");
                return null;
            }
            System.out.println( "DEBUG AUTO: Router selected = " + routedAgent.getName());
            System.out.println( "DEBUG AUTO: Confidence = " + result.getConfidence());
            System.out.println( "DEBUG AUTO: Reason = " + result.getReason());
            for (AgentInfo availableAgent : availableAgents) {
                if (availableAgent == null) {
                    continue;
                }
                boolean idMatches = routedAgent.getId() != null && availableAgent.getId() != null && routedAgent.getId() .equalsIgnoreCase( availableAgent.getId());
                boolean nameMatches = routedAgent.getName() != null && availableAgent.getName() != null && routedAgent.getName() .equalsIgnoreCase( availableAgent.getName());
                if (idMatches || nameMatches) {
                    System.out.println( "DEBUG AUTO: Matched local agent = " + availableAgent.getName());
                    System.out.println( "DEBUG AUTO: Local endpoint = " + availableAgent.getEndpoint());
                    return availableAgent;
                }
            }
            System.out.println( "DEBUG AUTO: Selected router agent " + "was not found in availableAgents");
            return null;
        }
        catch (Exception exception) {
            System.out.println( "DEBUG AUTO: Router call FAILED");
            System.out.println( "DEBUG AUTO: " + exception.getClass().getName() + ": " + exception.getMessage());
            exception.printStackTrace();
            return null;
        }
    }
    private AgentInfo findAgent( String... keywords) {
        if (availableAgents == null) {
            return null;
        }
        for (AgentInfo agent : availableAgents) {
            if (agent == null) {
                continue;
            }
            String searchable = ( safe( agent.getName()) + " " + safe( agent.getDescription()) + " " + safe( agent.getType()) ) .toLowerCase();
            for (String keyword : keywords) {
                if (keyword != null && searchable.contains( keyword.toLowerCase())) {
                    return agent;
                }
            }
        }
        return null;
    }
    private boolean containsAny( String text, String... values) {
        if (text == null || values == null) {
            return false;
        }
        String normalized = text.toLowerCase();
        for (String value : values) {
            if (value != null && normalized.contains( value.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    private boolean isAutoSelect( AgentInfo agent) {
        return agent != null && "__AUTO__" .equals( agent.getId());
    }
    private String safe( String value) {
        return value == null ? "" : value;
    }
    private void newChat() {
        contextId = UUID.randomUUID() .toString();
        messageTextArea .setText( "");
        responseTextArea .setText( "");
        if (selectedAgent == null) {
            statusLabel .setText( "Ready");
        }
        else if (isAutoSelect( selectedAgent)) {
            statusLabel .setText( "Auto Select Ready");
        }
        else {
            statusLabel .setText( "New chat with " + selectedAgent .getName());
        }
        messageTextArea .requestFocusInWindow();
        System.out.println( "New A2A context: " + contextId);
    }
    private String getRootMessage( Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current.getMessage() != null) {
            return current .getMessage();
        }
        return current .getClass() .getSimpleName();
    }
    public static void main( String[] args) {
        SwingUtilities .invokeLater( () -> {
            AIChatbotClient dialog = new AIChatbotClient( null); dialog .setLocationRelativeTo( null); dialog .setVisible( true);
        }
        );
    }
}
