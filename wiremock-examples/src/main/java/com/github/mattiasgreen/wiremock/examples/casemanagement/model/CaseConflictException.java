package com.github.mattiasgreen.wiremock.examples.casemanagement.model;

public class CaseConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorPayload;

    public CaseConflictException(int statusCode, String errorPayload) {
        super("Case state invariant conflict [" + statusCode + "]: " + errorPayload);
        this.statusCode = statusCode;
        this.errorPayload = errorPayload;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorPayload() {
        return errorPayload;
    }
}
