package com.ai.aicommunity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileUploadResponse {

    private Long id;
    private String originalName;
    private String contentType;
    private Long size;
    private String downloadUrl;
}
