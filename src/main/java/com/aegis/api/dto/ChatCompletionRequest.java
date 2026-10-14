package com.aegis.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest extends ExtensibleJson {

    @NotBlank
    private String model;

    @NotEmpty
    private List<@Valid ChatMessage> messages;

    private Boolean stream;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    @JsonIgnore
    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }

    /** Shallow copy for a provider that knows the model under another name. */
    public ChatCompletionRequest withModel(String upstreamModel) {
        ChatCompletionRequest copy = new ChatCompletionRequest();
        copy.model = upstreamModel;
        copy.messages = messages;
        copy.stream = stream;
        getAdditionalProperties().forEach(copy::setAdditionalProperty);
        return copy;
    }
}
