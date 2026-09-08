package com.health.social.donate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 公益基金会接口客户端（HTTP）。
 *
 * <p>生产注意：
 * <ul>
 *   <li>必须设置 <b>连接/读超时</b>，否则基金会侧抖动会拖垮本服务的线程池；</li>
 *   <li>必须保证接口<b>幂等</b>：以 {@code bizNo} 作为幂等键，由基金会侧去重，
 *       这样本侧的重试才不会产生重复捐赠；</li>
 *   <li>建议叠加熔断（Resilience4j / Sentinel），连续失败时快速失败而不是堆积线程。</li>
 * </ul>
 */
@Slf4j
@Component
public class FoundationClient {

    private final RestClient restClient;

    public FoundationClient(@Value("${health.donate.foundation-base-url:http://127.0.0.1:9099/mock-foundation}")
                            String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultStatusHandler(code -> code.value() >= 400, (request, response) -> {
                    throw new FoundationException("基金会接口返回异常状态: " + response.getStatusCode());
                })
                .build();
    }

    /**
     * 推送单笔打赏
     */
    public DonateResponse donate(DonateRequest request) {
        try {
            DonateResponse resp = restClient.post()
                    .uri("/donate")
                    .body(request)
                    .retrieve()
                    .body(DonateResponse.class);
            if (resp == null) {
                throw new FoundationException("基金会返回空响应");
            }
            return resp;
        } catch (FoundationException e) {
            throw e;
        } catch (Exception e) {
            // 网络异常 / 超时：交给上层重试
            throw new FoundationException("调用基金会失败: " + e.getMessage(), e);
        }
    }

    /**
     * 拉取指定日期的基金会账单（T+1 日终对账）
     */
    public List<FoundationBill> fetchDailyBills(LocalDate bizDate) {
        try {
            FoundationBill[] arr = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/bills")
                            .queryParam("date", bizDate.toString())
                            .build())
                    .retrieve()
                    .body(FoundationBill[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            throw new FoundationException("拉取基金会账单失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询单笔（对账差异排查用）
     */
    public FoundationBill queryByBizNo(String bizNo) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/bill").queryParam("bizNo", bizNo).build())
                    .retrieve()
                    .body(FoundationBill.class);
        } catch (Exception e) {
            throw new FoundationException("查询基金会流水失败: " + e.getMessage(), e);
        }
    }

    /** 金额比较工具：避免 BigDecimal.equals 的精度语义坑 */
    public static boolean amountEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    /** 基金会异常 */
    public static class FoundationException extends RuntimeException {
        public FoundationException(String message) {
            super(message);
        }

        public FoundationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
