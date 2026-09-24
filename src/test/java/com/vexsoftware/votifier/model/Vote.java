package com.vexsoftware.votifier.model;

/**
 * Stand-in for the real Votifier class so the hook can be tested without the plugin.
 */
public class Vote {

    private final String username;
    private final String serviceName;
    private final String timeStamp;

    public Vote(String username, String serviceName) {
        this(username, serviceName, "1");
    }

    public Vote(String username, String serviceName, String timeStamp) {
        this.username = username;
        this.serviceName = serviceName;
        this.timeStamp = timeStamp;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    public String getUsername() {
        return username;
    }

    public String getServiceName() {
        return serviceName;
    }
}
