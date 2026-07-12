package com.ai.aicommunity.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TrainingCampDTO {

    @NotBlank(message = "训练营标题不能为空")
    @Size(max = 100, message = "训练营标题不能超过100个字符")
    private String title;

    @Size(max = 1000, message = "训练营介绍不能超过1000个字符")
    private String description;

    @NotNull(message = "名额不能为空")
    @Min(value = 1, message = "名额必须大于0")
    private Integer stock;

    @NotNull(message = "开始时间不能为空")
    @Future(message = "开始时间必须晚于当前时间")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
}
