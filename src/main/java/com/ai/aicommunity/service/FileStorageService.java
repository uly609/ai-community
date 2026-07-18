package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.FileUploadResponse;
import com.ai.aicommunity.entity.CommunityFile;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.CommunityFileMapper;
import com.ai.aicommunity.utils.UserHolder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Path STORAGE_ROOT = Path.of("uploads").toAbsolutePath().normalize();
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    private final CommunityFileMapper communityFileMapper;

    public FileStorageService(CommunityFileMapper communityFileMapper) {
        this.communityFileMapper = communityFileMapper;
    }

    public FileUploadResponse upload(MultipartFile file) {
        Long userId = requireUser();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件不能超过10MB");
        }

        try {
            Files.createDirectories(STORAGE_ROOT);
            String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null
                    ? "unknown" : file.getOriginalFilename());
            String storedName = UUID.randomUUID() + suffix(originalName);
            Path target = STORAGE_ROOT.resolve(storedName).normalize();
            file.transferTo(target);

            CommunityFile record = new CommunityFile();
            record.setUserId(userId);
            record.setOriginalName(originalName);
            record.setStoredName(storedName);
            record.setContentType(file.getContentType());
            record.setSize(file.getSize());
            record.setStoragePath(target.toString());
            record.setCreateTime(LocalDateTime.now());
            communityFileMapper.insert(record);

            return new FileUploadResponse(
                    record.getId(),
                    record.getOriginalName(),
                    record.getContentType(),
                    record.getSize(),
                    "/files/" + record.getId() + "/download"
            );
        } catch (IOException e) {
            throw new IllegalStateException("文件上传失败", e);
        }
    }

    public DownloadFile download(Long fileId) {
        CommunityFile file = communityFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        Resource resource = new FileSystemResource(file.getStoragePath());
        if (!resource.exists()) {
            throw new BusinessException("文件已丢失");
        }
        return new DownloadFile(file.getOriginalName(), file.getContentType(), resource);
    }

    private String suffix(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index);
    }

    private Long requireUser() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        return userId;
    }

    public record DownloadFile(String originalName, String contentType, Resource resource) {
    }
}
