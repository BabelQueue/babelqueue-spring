package com.babelqueue.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the BabelQueue Spring adapter (prefix {@code babelqueue}). */
@ConfigurationProperties(prefix = "babelqueue")
public class BabelQueueProperties {

    /** Default queue used by the publisher and when building envelopes from messages. */
    private String defaultQueue = "default";

    public String getDefaultQueue() {
        return defaultQueue;
    }

    public void setDefaultQueue(String defaultQueue) {
        this.defaultQueue = defaultQueue;
    }
}
