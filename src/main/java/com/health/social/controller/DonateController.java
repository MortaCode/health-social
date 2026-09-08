package com.health.social.controller;

import com.health.social.common.Result;
import com.health.social.common.UserContext;
import com.health.social.donate.DonateService;
import com.health.social.ratelimit.RateLimit;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 公益打赏接口。
 *
 * <p>请求必须携带幂等号 {@code bizNo}：网络重试 / 用户连点都只会扣一次款。
 */
@RestController
@RequestMapping("/donate")
public class DonateController {

    private final DonateService donateService;

    public DonateController(DonateService donateService) {
        this.donateService = donateService;
    }

    @RateLimit(api = "donate", permits = 5)
    @PostMapping
    public Result<DonateService.DonateResult> donate(@RequestBody DonateRequest req) {
        String bizNo = req.getBizNo();
        if (bizNo == null || bizNo.isBlank()) {
            bizNo = "D" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
        }
        return Result.ok(donateService.donate(UserContext.getUserId(), req.getProjectId(), req.getAmount(), bizNo));
    }

    /** 手动触发对账（运维用） */
    @PostMapping("/reconcile")
    public Result<String> reconcile() {
        return Result.ok("请使用 /donate/reconcile/trigger 或直接等待定时任务（默认 02:30）");
    }

    @GetMapping("/{bizNo}")
    public Result<String> query(@PathVariable String bizNo) {
        return Result.ok("查询打赏单请走订单服务，此处仅演示入口: " + bizNo);
    }

    @Data
    public static class DonateRequest {

        private Long projectId;

        private BigDecimal amount;

        /** 幂等号，不传则服务端自动生成 */
        private String bizNo;
    }
}
