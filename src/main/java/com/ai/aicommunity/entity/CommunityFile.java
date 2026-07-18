package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("community_file")
public class CommunityFile {

    private Long id;
    private Long userId;
    private String originalName;
    private String storedName;
    private String contentType;
    private Long size;
    private String storagePath;
    private LocalDateTime createTime;
}
