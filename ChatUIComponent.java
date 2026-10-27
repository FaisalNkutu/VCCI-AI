package com.softknf.chat;

import com.calian.paradigm.ui.framework.UIComponent;
import com.calian.paradigm.ui.framework.framework.UIDelegate;

public class ChatUIComponent extends UIComponent {

    public ChatUIComponent() {
        super();
    }

    @Override
    protected UIDelegate createDelegate() {
        return new ChatUIDelegate(this);
    }
}