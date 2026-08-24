package io.fiq.server.api;

import jakarta.ws.rs.core.Response;

public final class ApiException extends RuntimeException {
    private final Response.StatusType status;
    private final String code;

    public ApiException(Response.Status status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(int statusCode, String code, String message) {
        super(message);
        this.status =
                Response.Status.fromStatusCode(statusCode) != null
                        ? Response.Status.fromStatusCode(statusCode)
                        : new Response.StatusType() {
                            @Override
                            public int getStatusCode() {
                                return statusCode;
                            }

                            @Override
                            public Response.Status.Family getFamily() {
                                return Response.Status.Family.familyOf(statusCode);
                            }

                            @Override
                            public String getReasonPhrase() {
                                return "Unprocessable Entity";
                            }
                        };
        this.code = code;
    }

    public Response.StatusType status() {
        return status;
    }

    public String code() {
        return code;
    }
}
