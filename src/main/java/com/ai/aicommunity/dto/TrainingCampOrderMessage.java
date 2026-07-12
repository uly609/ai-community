package com.ai.aicommunity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCampOrderMessage implements Serializable {

    private Long campId;

    private Long userId;
}
