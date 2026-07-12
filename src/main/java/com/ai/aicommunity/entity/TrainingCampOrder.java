package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_camp_order")
public class TrainingCampOrder {

    private Long id;

    private Long campId;

    private Long userId;

    private Integer status;

    private LocalDateTime payExpireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
