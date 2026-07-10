package com.ai.aicommunity.utils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

/**
 * 文章ID布隆过滤器，防止缓存穿透。
 */
public class BloomFilterUtil {

    private static final BloomFilter<Long> ARTICLE_FILTER =
            BloomFilter.create(Funnels.longFunnel(), 1000000, 0.01);

    public static void addArticleId(Long id) {
        ARTICLE_FILTER.put(id);
    }

    public static boolean mightExist(Long id) {
        return ARTICLE_FILTER.mightContain(id);
    }
}
