package com.stayon.stayon_backend.entity;

public enum Role {
    MANAGER("manager"),
    ADMIN("admin"),
    STAFF("staff");
    private String role;
    Role(String role) {
        this.role = role;
    }
    public static Role getRole(String role) {
        for (Role r : Role.values()) {
            if (r.role.equals(role)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Invalid role: " + role);
    }
}