package io.fiq.domain;

public enum Role {
    VIEWER,
    OPERATOR,
    APPROVER,
    ADMIN;

    public boolean canOperate() {
        return this == OPERATOR || this == ADMIN;
    }

    public boolean canApprove() {
        return this == APPROVER || this == ADMIN;
    }
}
