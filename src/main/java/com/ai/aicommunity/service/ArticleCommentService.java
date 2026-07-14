package com.ai.aicommunity.service;

import com.ai.aicommunity.config.RocketMQConfig;
import com.ai.aicommunity.dto.ArticleCommentDTO;
import com.ai.aicommunity.dto.CommunityNotificationMessage;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.entity.ArticleComment;
import com.ai.aicommunity.entity.MqOutboxMessage;
import com.ai.aicommunity.entity.User;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.ArticleCommentMapper;
import com.ai.aicommunity.mapper.ArticleMapper;
import com.ai.aicommunity.mapper.UserMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.ai.aicommunity.vo.ArticleCommentVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ArticleCommentService {

    private final ArticleCommentMapper articleCommentMapper;
    private final ArticleMapper articleMapper;
    private final UserMapper userMapper;
    private final ArticleService articleService;
    private final MqOutboxService mqOutboxService;
    private final ObjectMapper objectMapper;

    public ArticleCommentService(ArticleCommentMapper articleCommentMapper,
                                 ArticleMapper articleMapper,
                                 UserMapper userMapper,
                                 ArticleService articleService,
                                 MqOutboxService mqOutboxService,
                                 ObjectMapper objectMapper) {
        this.articleCommentMapper = articleCommentMapper;
        this.articleMapper = articleMapper;
        this.userMapper = userMapper;
        this.articleService = articleService;
        this.mqOutboxService = mqOutboxService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long articleId, ArticleCommentDTO dto) {
        Long userId = requireUserId();
        Article article = getArticle(articleId);

        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        Long replyUserId = null;
        if (parentId != 0L) {
            ArticleComment parent = articleCommentMapper.selectById(parentId);
            if (parent == null || !articleId.equals(parent.getArticleId()) || parent.getStatus() == 0) {
                throw new BusinessException("回复的评论不存在或已删除");
            }
            replyUserId = parent.getUserId();
        }

        ArticleComment comment = new ArticleComment();
        comment.setArticleId(articleId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setReplyUserId(replyUserId);
        comment.setContent(dto.getContent().trim());
        comment.setStatus(1);
        comment.setCreateTime(LocalDateTime.now());
        comment.setUpdateTime(LocalDateTime.now());
        articleCommentMapper.insert(comment);

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .setSql("comment_count = comment_count + 1")
                .set(Article::getUpdateTime, LocalDateTime.now()));
        final Long targetReplyUserId = replyUserId;
        MqOutboxMessage notificationOutbox = createNotificationOutbox(article, comment, parentId, targetReplyUserId);
        runAfterCommit(() -> {
            articleService.invalidateDetailCache(articleId);
            if (notificationOutbox != null) {
                mqOutboxService.sendNow(notificationOutbox,
                        notificationPayload(article, comment, parentId, targetReplyUserId));
            }
        });
        return comment.getId();
    }

    public Page<ArticleCommentVO> page(Long articleId, Integer current, Integer size) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        Page<ArticleComment> commentPage = articleCommentMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ArticleComment>()
                        .eq(ArticleComment::getArticleId, articleId)
                        .orderByAsc(ArticleComment::getParentId)
                        .orderByAsc(ArticleComment::getCreateTime));
        return toCommentVOPage(commentPage);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long articleId, Long commentId) {
        Long userId = requireUserId();
        ArticleComment comment = articleCommentMapper.selectById(commentId);
        if (comment == null || !articleId.equals(comment.getArticleId())) {
            throw new BusinessException("评论不存在");
        }
        if (!userId.equals(comment.getUserId())) {
            throw new BusinessException("只能删除自己的评论");
        }
        if (comment.getStatus() == 0) {
            return;
        }

        int updated = articleCommentMapper.update(null, new LambdaUpdateWrapper<ArticleComment>()
                .eq(ArticleComment::getId, commentId)
                .eq(ArticleComment::getStatus, 1)
                .set(ArticleComment::getStatus, 0)
                .set(ArticleComment::getContent, "该评论已删除")
                .set(ArticleComment::getUpdateTime, LocalDateTime.now()));
        if (updated == 0) {
            return;
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .gt(Article::getCommentCount, 0)
                .setSql("comment_count = comment_count - 1")
                .set(Article::getUpdateTime, LocalDateTime.now()));
        runAfterCommit(() -> articleService.invalidateDetailCache(articleId));
    }

    private Article getArticle(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        return article;
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        return userId;
    }

    private void runAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private MqOutboxMessage createNotificationOutbox(Article article,
                                                      ArticleComment comment,
                                                      Long parentId,
                                                      Long replyUserId) {
        CommunityNotificationMessage payload = notificationPayload(article, comment, parentId, replyUserId);
        if (payload == null) {
            return null;
        }
        try {
            return mqOutboxService.savePending(
                    RocketMQConfig.COMMUNITY_NOTIFICATION_TOPIC,
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new BusinessException("创建通知任务失败");
        }
    }

    private CommunityNotificationMessage notificationPayload(Article article,
                                                             ArticleComment comment,
                                                             Long parentId,
                                                             Long replyUserId) {
        Long recipientUserId = parentId == 0L ? article.getUserId() : replyUserId;
        if (recipientUserId == null || recipientUserId.equals(comment.getUserId())) {
            return null;
        }
        String type = parentId == 0L ? "ARTICLE_COMMENT" : "COMMENT_REPLY";
        String content = parentId == 0L ? "评论了你的文章" : "回复了你的评论";
        return new CommunityNotificationMessage(recipientUserId, comment.getUserId(), type,
                article.getId(), comment.getId(), content);
    }

    private Page<ArticleCommentVO> toCommentVOPage(Page<ArticleComment> commentPage) {
        Set<Long> userIds = new HashSet<>();
        for (ArticleComment comment : commentPage.getRecords()) {
            userIds.add(comment.getUserId());
            if (comment.getReplyUserId() != null) {
                userIds.add(comment.getReplyUserId());
            }
        }
        Map<Long, String> nicknameMap = loadNicknameMap(userIds);
        Long currentUserId = UserHolder.getUserId();

        List<ArticleCommentVO> records = commentPage.getRecords().stream()
                .map(comment -> toCommentVO(comment, nicknameMap, currentUserId))
                .toList();
        Page<ArticleCommentVO> page = new Page<>(commentPage.getCurrent(), commentPage.getSize(), commentPage.getTotal());
        page.setRecords(records);
        return page;
    }

    private ArticleCommentVO toCommentVO(ArticleComment comment,
                                         Map<Long, String> nicknameMap,
                                         Long currentUserId) {
        ArticleCommentVO vo = new ArticleCommentVO();
        vo.setId(comment.getId());
        vo.setArticleId(comment.getArticleId());
        vo.setUserId(comment.getUserId());
        vo.setUserNickname(nicknameMap.getOrDefault(comment.getUserId(), "已注销用户"));
        vo.setParentId(comment.getParentId());
        vo.setReplyUserId(comment.getReplyUserId());
        vo.setReplyUserNickname(comment.getReplyUserId() == null ? null
                : nicknameMap.getOrDefault(comment.getReplyUserId(), "已注销用户"));
        vo.setContent(comment.getContent());
        vo.setStatus(comment.getStatus());
        vo.setCanDelete(comment.getUserId().equals(currentUserId) && comment.getStatus() == 1);
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }

    private Map<Long, String> loadNicknameMap(Set<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        for (User user : userMapper.selectBatchIds(userIds)) {
            String nickname = user.getNickname();
            result.put(user.getId(), nickname == null || nickname.isBlank() ? user.getUsername() : nickname);
        }
        return result;
    }
}
