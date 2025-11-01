package com.acme.reliable.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration for messaging naming patterns and conventions.
 */
@ConfigurationProperties("messaging")
public class MessagingConfig {

    private QueueNaming queueNaming = new QueueNaming();
    private TopicNaming topicNaming = new TopicNaming();

    public QueueNaming getQueueNaming() {
        return queueNaming;
    }

    public void setQueueNaming(QueueNaming queueNaming) {
        this.queueNaming = queueNaming;
    }

    public TopicNaming getTopicNaming() {
        return topicNaming;
    }

    public void setTopicNaming(TopicNaming topicNaming) {
        this.topicNaming = topicNaming;
    }

    public static class QueueNaming {
        private String commandPrefix = "APP.CMD.";
        private String queueSuffix = ".Q";
        private String replyQueue = "APP.CMD.REPLY.Q";

        public String getCommandPrefix() {
            return commandPrefix;
        }

        public void setCommandPrefix(String commandPrefix) {
            this.commandPrefix = commandPrefix;
        }

        public String getQueueSuffix() {
            return queueSuffix;
        }

        public void setQueueSuffix(String queueSuffix) {
            this.queueSuffix = queueSuffix;
        }

        public String getReplyQueue() {
            return replyQueue;
        }

        public void setReplyQueue(String replyQueue) {
            this.replyQueue = replyQueue;
        }

        /**
         * Build a command queue name from a command name.
         * Example: CreateUser -> APP.CMD.CreateUser.Q
         */
        public String buildCommandQueue(String commandName) {
            return commandPrefix + commandName + queueSuffix;
        }
    }

    public static class TopicNaming {
        private String eventPrefix = "events.";

        public String getEventPrefix() {
            return eventPrefix;
        }

        public void setEventPrefix(String eventPrefix) {
            this.eventPrefix = eventPrefix;
        }

        /**
         * Build an event topic name from a command name.
         * Example: CreateUser -> events.CreateUser
         */
        public String buildEventTopic(String commandName) {
            return eventPrefix + commandName;
        }
    }
}
