package com.github.mattiasgreen.wiremock.examples.casemanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentDto {

    private String id;
    private String caseId;
    private String text;

    public CommentDto() {}

    public CommentDto(String id, String caseId, String text) {
        this.id = id;
        this.caseId = caseId;
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return "CommentDto{" + "id='" + id + '\'' + ", caseId='" + caseId + '\'' + ", text='" + text + '\'' + '}';
    }
}
