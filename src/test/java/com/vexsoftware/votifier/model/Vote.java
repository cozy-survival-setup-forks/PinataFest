package com.vexsoftware.votifier.model;

/**
 * Stand-in for the real Votifier class so the hook can be tested without the plugin.
 */
public class Vote {

    private final String username;
    private final String serviceName;

    public Vote(String username, String serviceName) {
        this.username = username;
        this.serviceName = serviceName;
    }

    public String getUsername() {
        return username;
    }

    public String getServiceName() {
        return serviceName;
    }
}
