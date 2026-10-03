package com.github.mattiasgreen.wiremock.examples.casemanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseDto {

    private String id;
    private String title;
    private String status;

    public CaseDto() {}

    public CaseDto(String id, String title, String status) {
        this.id = id;
        this.title = title;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "CaseDto{" + "id='" + id + '\'' + ", title='" + title + '\'' + ", status='" + status + '\'' + '}';
    }
}
