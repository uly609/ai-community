package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.TrainingCampDTO;
import com.ai.aicommunity.dto.TrainingCampQualificationResponse;
import com.ai.aicommunity.entity.TrainingCamp;
import com.ai.aicommunity.entity.TrainingCampOrder;
import com.ai.aicommunity.service.TrainingCampService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/training-camps")
public class TrainingCampController {

    private final TrainingCampService trainingCampService;

    public TrainingCampController(TrainingCampService trainingCampService) {
        this.trainingCampService = trainingCampService;
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody TrainingCampDTO dto) {
        return Result.success(trainingCampService.create(dto));
    }

    @GetMapping
    public Result<Page<TrainingCamp>> list(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(trainingCampService.page(current, size));
    }

    @PostMapping("/{campId}/preload")
    public Result<String> preload(@PathVariable Long campId) {
        trainingCampService.preload(campId);
        return Result.success("预热成功");
    }

    @PostMapping("/{campId}/qualification")
    public Result<TrainingCampQualificationResponse> applyQualification(@PathVariable Long campId) {
        return Result.success(trainingCampService.applyQualification(campId));
    }

    @PostMapping("/{campId}/enroll")
    public Result<String> enroll(@PathVariable Long campId,
                                 @RequestParam(required = false) String qualificationToken) {
        trainingCampService.enroll(campId, qualificationToken);
        return Result.success("报名资格已锁定，订单创建中");
    }

    @GetMapping("/orders/me")
    public Result<Page<TrainingCampOrder>> myOrders(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(trainingCampService.myOrders(current, size));
    }

    @PostMapping("/orders/{orderId}/pay")
    public Result<String> pay(@PathVariable Long orderId) {
        trainingCampService.pay(orderId);
        return Result.success("支付成功");
    }

    @PostMapping("/orders/{orderId}/cancel")
    public Result<String> cancel(@PathVariable Long orderId) {
        trainingCampService.cancel(orderId);
        return Result.success("取消成功");
    }
}
