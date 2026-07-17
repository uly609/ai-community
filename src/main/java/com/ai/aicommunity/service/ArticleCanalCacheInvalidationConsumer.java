package com.ai.aicommunity.service;

import com.ai.aicommunity.config.CanalArticleCacheProperties;
import com.ai.aicommunity.utils.BloomFilterUtil;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.canal.article-cache.enabled", havingValue = "true")
public class ArticleCanalCacheInvalidationConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ArticleCanalCacheInvalidationConsumer.class);

    private final CanalArticleCacheProperties properties;
    private final ArticleService articleService;

    private volatile boolean running;
    private Thread worker;
    private CanalConnector connector;

    public ArticleCanalCacheInvalidationConsumer(CanalArticleCacheProperties properties,
                                                 ArticleService articleService) {
        this.properties = properties;
        this.articleService = articleService;
    }

    // 启动 Canal 消费线程：监听 article 表 binlog，数据库变了就触发缓存失效。
    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::consumeLoop, "article-canal-cache-invalidation");
        worker.setDaemon(true);
        worker.start();
    }

    // 停止 Canal 消费线程，应用关闭时会走这里。
    @Override
    public void stop() {
        running = false;
        if (connector != null) {
            try {
                connector.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // Canal 主循环：拉一批 binlog，处理成功 ack，失败 rollback 后下次再拉。
    private void consumeLoop() {
        connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(properties.getHost(), properties.getPort()),
                properties.getDestination(),
                properties.getUsername(),
                properties.getPassword()
        );
        while (running) {
            try {
                connector.connect();
                connector.subscribe(properties.getSubscribeRegex());
                connector.rollback();
                consumeMessages();
            } catch (Exception e) {
                log.warn("Canal article cache consumer error: {}", e.getMessage());
                sleepQuietly(properties.getIdleSleepMillis());
            } finally {
                try {
                    connector.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // 持续消费 Canal 消息，没有消息就稍微睡一下，避免空转。
    private void consumeMessages() {
        while (running) {
            Message message = connector.getWithoutAck(properties.getBatchSize());
            long batchId = message.getId();
            List<CanalEntry.Entry> entries = message.getEntries();
            if (batchId == -1 || entries.isEmpty()) {
                sleepQuietly(properties.getIdleSleepMillis());
                continue;
            }
            try {
                handleEntries(entries);
                connector.ack(batchId);
            } catch (Exception e) {
                connector.rollback(batchId);
                log.warn("Canal article cache batch rollback: {}", e.getMessage());
            }
        }
    }

    // 只关心 article 表的行变更，拿到文章 id 后让文章详情缓存失效。
    private void handleEntries(List<CanalEntry.Entry> entries) throws InvalidProtocolBufferException {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();
            if (eventType != CanalEntry.EventType.INSERT
                    && eventType != CanalEntry.EventType.UPDATE
                    && eventType != CanalEntry.EventType.DELETE) {
                continue;
            }
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                Long articleId = extractArticleId(eventType, rowData);
                if (articleId == null) {
                    continue;
                }
                if (eventType == CanalEntry.EventType.INSERT) {
                    BloomFilterUtil.addArticleId(articleId);
                }
                articleService.invalidateDetailCache(articleId);
            }
        }
    }

    // update/insert 从 afterColumns 拿 id，delete 从 beforeColumns 拿 id。
    private Long extractArticleId(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        List<CanalEntry.Column> columns = eventType == CanalEntry.EventType.DELETE
                ? rowData.getBeforeColumnsList()
                : rowData.getAfterColumnsList();
        for (CanalEntry.Column column : columns) {
            if ("id".equalsIgnoreCase(column.getName())) {
                return Long.valueOf(column.getValue());
            }
        }
        return null;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
