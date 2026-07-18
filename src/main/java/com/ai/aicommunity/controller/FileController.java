package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.FileUploadResponse;
import com.ai.aicommunity.service.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/upload")
    public Result<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileStorageService.upload(file));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FileStorageService.DownloadFile file = fileStorageService.download(id);
        MediaType mediaType = file.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(file.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.originalName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(file.resource());
    }
}
