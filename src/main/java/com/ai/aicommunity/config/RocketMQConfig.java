package com.ai.aicommunity.config;

public class RocketMQConfig {

    public static final String TRAINING_CAMP_ORDER_TOPIC = "training-camp-order-topic";
    public static final String TRAINING_CAMP_ORDER_CONSUMER_GROUP = "training-camp-order-consumer-group";
    public static final String TRAINING_CAMP_ORDER_TIMEOUT_TOPIC = "training-camp-order-timeout-topic";
    public static final String TRAINING_CAMP_ORDER_TIMEOUT_CONSUMER_GROUP = "training-camp-order-timeout-consumer-group";
    public static final String TRAINING_CAMP_REDIS_ROLLBACK_TOPIC = "training-camp-redis-rollback-topic";
    public static final String TRAINING_CAMP_REDIS_ROLLBACK_CONSUMER_GROUP = "training-camp-redis-rollback-consumer-group";
    public static final String COMMUNITY_NOTIFICATION_TOPIC = "community-notification-topic";
    public static final String COMMUNITY_NOTIFICATION_CONSUMER_GROUP = "community-notification-consumer-group";
    public static final String ARTICLE_AI_SUMMARY_TOPIC = "article-ai-summary-topic";
    public static final String ARTICLE_AI_SUMMARY_CONSUMER_GROUP = "article-ai-summary-consumer-group";

    private RocketMQConfig() {
    }
}
