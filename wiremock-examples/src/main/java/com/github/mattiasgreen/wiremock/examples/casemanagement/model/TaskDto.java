package com.github.mattiasgreen.wiremock.examples.casemanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskDto {

    private String id;
    private String caseId;
    private String description;
    private boolean completed;

    public TaskDto() {
    }

    public TaskDto(String id, String caseId, String description, boolean completed) {
        this.id = id;
        this.caseId = caseId;
        this.description = description;
        this.completed = completed;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    @Override
    public String toString() {
        return "TaskDto{" +
                "id='" + id + '\'' +
                ", caseId='" + caseId + '\'' +
                ", description='" + description + '\'' +
                ", completed=" + completed +
                '}';
    }
}
