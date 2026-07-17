package com.ai.aicommunity.service;

import com.ai.aicommunity.entity.CacheInvalidationTask;
import com.ai.aicommunity.mapper.CacheInvalidationTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CacheInvalidationTaskService {

    private static final String CACHE_TYPE_ARTICLE_DETAIL = "ARTICLE_DETAIL";
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAILED = 2;
    private static final int MAX_RETRY_COUNT = 5;

    private final CacheInvalidationTaskMapper taskMapper;

    private ArticleService articleService;

    public CacheInvalidationTaskService(CacheInvalidationTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    // Spring 为了解决循环依赖，ArticleService 构造完后会把自己传进来。
    public void setArticleService(ArticleService articleService) {
        this.articleService = articleService;
    }

    // 记录文章详情缓存删除失败任务，后面定时任务会继续删。
    public void saveArticleDetailTask(Long articleId, Throwable e) {
        LocalDateTime now = LocalDateTime.now();
        CacheInvalidationTask task = new CacheInvalidationTask();
        task.setCacheType(CACHE_TYPE_ARTICLE_DETAIL);
        task.setBizId(articleId);
        task.setStatus(STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryTime(now.plusSeconds(5));
        task.setErrorMessage(truncate(e.getMessage()));
        task.setCreateTime(now);
        task.setUpdateTime(now);
        // 删文章缓存失败时先记下来，别让旧缓存因为一次 Redis 抖动就一直留着。
        taskMapper.insert(task);
    }

    @Scheduled(fixedDelay = 5000)
    // 定时扫描待补偿任务，到了重试时间就再执行一次缓存删除。
    public void retryPendingTasks() {
        // 每 5 秒扫一批到期任务，重试次数太多的先不扫，避免失败任务拖垮系统。
        List<CacheInvalidationTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<CacheInvalidationTask>()
                        .in(CacheInvalidationTask::getStatus, STATUS_PENDING, STATUS_FAILED)
                        .le(CacheInvalidationTask::getNextRetryTime, LocalDateTime.now())
                        .lt(CacheInvalidationTask::getRetryCount, MAX_RETRY_COUNT)
                        .orderByAsc(CacheInvalidationTask::getCreateTime)
                        .last("LIMIT 20")
        );

        for (CacheInvalidationTask task : tasks) {
            retryTask(task);
        }
    }

    // 执行单条补偿任务：现在只支持文章详情缓存失效。
    private void retryTask(CacheInvalidationTask task) {
        try {
            if (CACHE_TYPE_ARTICLE_DETAIL.equals(task.getCacheType())) {
                // 现在只做文章详情缓存失效，后面要补别的缓存类型也可以复用这张表。
                articleService.invalidateArticleDetailCacheNow(task.getBizId());
            }
            markDone(task.getId());
        } catch (Exception e) {
            markFailed(task, e);
        }
    }

    // 补偿成功后标记为 DONE。
    private void markDone(Long id) {
        taskMapper.update(
                null,
                new LambdaUpdateWrapper<CacheInvalidationTask>()
                        .eq(CacheInvalidationTask::getId, id)
                        .set(CacheInvalidationTask::getStatus, STATUS_DONE)
                        .set(CacheInvalidationTask::getUpdateTime, LocalDateTime.now())
        );
    }

    // 补偿失败后增加重试次数，并推迟下一次重试时间。
    private void markFailed(CacheInvalidationTask task, Throwable e) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(Math.min(60, 5L * (retryCount + 1)));
        taskMapper.update(
                null,
                new LambdaUpdateWrapper<CacheInvalidationTask>()
                        .eq(CacheInvalidationTask::getId, task.getId())
                        .set(CacheInvalidationTask::getStatus, STATUS_FAILED)
                        .set(CacheInvalidationTask::getRetryCount, retryCount + 1)
                        .set(CacheInvalidationTask::getNextRetryTime, nextRetryTime)
                        .set(CacheInvalidationTask::getErrorMessage, truncate(e.getMessage()))
                        .set(CacheInvalidationTask::getUpdateTime, LocalDateTime.now())
        );
    }

    // 错误信息太长会塞不进数据库，这里截断一下。
    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
