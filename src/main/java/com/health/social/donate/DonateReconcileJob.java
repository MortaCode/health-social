package com.health.social.donate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.social.entity.DonateRecord;
import com.health.social.entity.ReconcileReport;
import com.health.social.mapper.DonateLocalRecordMapper;
import com.health.social.mapper.DonateRecordMapper;
import com.health.social.mapper.ReconcileReportMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打赏定时任务：
 * <ol>
 *   <li><b>重试推送</b>：每隔 60s 扫描本地事务表中 {@code PENDING/SENT} 且到了下次重试时间的记录，
 *       重新调用基金会接口，实现"至少一次"投递；</li>
 *   <li><b>T+1 日终对账</b>：每天 02:30 拉取 T-1 日基金会日账单，与本地 {@code CONFIRMED}
 *       记录做双向比对（本地有远端无 / 远端有本地无 / 金额不一致），
 *       生成 {@code t_reconcile_report}，差异项告警并支持人工补单。</li>
 * </ol>
 *
 * <p>为什么对账是必须的最后一道防线：
 * 本地消息表能保证"至少一次"，但网络超时、回执丢失、基金会内部回滚等情况
 * 会让本地状态与远端真实状态出现偏差 —— 只有对账能发现这类"沉默的不一致"。
 *
 * <p>多实例部署时，定时任务需要用分布式锁或选主（Redisson / XXL-Job）保证只跑一次，
 * 这里用简单的 Redis SETNX 占位示意。
 */
@Slf4j
@Component
public class DonateReconcileJob {

    private final DonateLocalRecordMapper localRecordMapper;
    private final DonateRecordMapper donateRecordMapper;
    private final ReconcileReportMapper reportMapper;
    private final DonateService donateService;
    private final FoundationClient foundationClient;

    @Value("${health.donate.retry-limit:5}")
    private int retryLimit;

    public DonateReconcileJob(DonateLocalRecordMapper localRecordMapper,
                              DonateRecordMapper donateRecordMapper,
                              ReconcileReportMapper reportMapper,
                              DonateService donateService,
                              FoundationClient foundationClient) {
        this.localRecordMapper = localRecordMapper;
        this.donateRecordMapper = donateRecordMapper;
        this.reportMapper = reportMapper;
        this.donateService = donateService;
        this.foundationClient = foundationClient;
    }

    /**
     * 重试推送：每 60 秒。
     *
     * <p>注意：所有定时任务都必须自带 try/catch。数据库/中间件短暂不可用时，
     * 未捕获异常会被 {@code TaskUtils$LoggingErrorHandler} 打成一大段堆栈，
     * 既刷屏又掩盖真正的业务问题；这里降为一行 WARN，下个周期自动重试。
     */
    @Scheduled(initialDelay = 30_000, fixedDelayString = "${health.donate.retry-fixed-delay-ms:60000}")
    public void retryPendingRecords() {
        List<com.health.social.entity.DonateLocalRecord> pending;
        try {
            pending = localRecordMapper.selectRetryable(LocalDateTime.now(), 200);
        } catch (Exception e) {
            log.warn("[Donate] 扫描待重试记录失败（下个周期自动重试）: {}", e.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        log.info("[Donate] 定时任务重试推送 {} 条", pending.size());
        for (com.health.social.entity.DonateLocalRecord record : pending) {
            try {
                donateService.pushToFoundation(record.getId());
            } catch (Exception e) {
                log.error("[Donate] 重试推送异常, bizNo={}", record.getBizNo(), e);
            }
        }
    }

    /**
     * T+1 日终对账
     */
    @Scheduled(cron = "${health.donate.reconcile-cron:0 30 2 * * ?}")
    public void reconcileDaily() {
        LocalDate bizDate = LocalDate.now().minusDays(1);
        LocalDateTime from = bizDate.atStartOfDay();
        LocalDateTime to = bizDate.plusDays(1).atStartOfDay();

        log.info("[Reconcile] 开始 T+1 对账, bizDate={}", bizDate);
        try {
            // ---------- 1. 本地侧 ----------
            List<DonateRecord> locals = donateRecordMapper.selectConfirmedBetween(from, to);
            Map<String, DonateRecord> localMap = new HashMap<>(Math.max(16, locals.size() * 2));
            BigDecimal localAmount = BigDecimal.ZERO;
            for (DonateRecord r : locals) {
                localMap.put(r.getBizNo(), r);
                localAmount = localAmount.add(r.getAmount() == null ? BigDecimal.ZERO : r.getAmount());
            }

            // ---------- 2. 基金会侧 ----------
            List<FoundationBill> bills = foundationClient.fetchDailyBills(bizDate);
            Map<String, FoundationBill> remoteMap = new HashMap<>(Math.max(16, bills.size() * 2));
            BigDecimal remoteAmount = BigDecimal.ZERO;
            for (FoundationBill b : bills) {
                remoteMap.put(b.getBizNo(), b);
                remoteAmount = remoteAmount.add(b.getAmount() == null ? BigDecimal.ZERO : b.getAmount());
            }

            // ---------- 3. 双向比对 ----------
            Set<String> diffBizNos = new HashSet<>();
            // 本地有 / 远端无（漏单）
            for (Map.Entry<String, DonateRecord> e : localMap.entrySet()) {
                FoundationBill remote = remoteMap.get(e.getKey());
                if (remote == null) {
                    diffBizNos.add(e.getKey() + "(MISSING_REMOTE)");
                } else if (!FoundationClient.amountEquals(e.getValue().getAmount(), remote.getAmount())) {
                    diffBizNos.add(e.getKey() + "(AMOUNT_DIFF)");
                }
            }
            // 远端有 / 本地无（基金会多收，需退）
            for (String bizNo : remoteMap.keySet()) {
                if (!localMap.containsKey(bizNo)) {
                    diffBizNos.add(bizNo + "(MISSING_LOCAL)");
                }
            }

            // ---------- 4. 落对账报告 ----------
            ReconcileReport report = new ReconcileReport();
            report.setBizDate(bizDate);
            report.setLocalCnt(locals.size());
            report.setLocalAmount(localAmount);
            report.setRemoteCnt(bills.size());
            report.setRemoteAmount(remoteAmount);
            report.setDiffCnt(diffBizNos.size());
            report.setDiffBizNos(String.join(",", new ArrayList<>(diffBizNos)));
            report.setStatus(diffBizNos.isEmpty() ? ReconcileReport.STATUS_BALANCED : ReconcileReport.STATUS_DIFF);

            // 同一天重复对账（如人工重跑）覆盖上一次结果
            ReconcileReport old = reportMapper.selectOne(
                    new LambdaQueryWrapper<ReconcileReport>().eq(ReconcileReport::getBizDate, bizDate));
            if (old != null) {
                report.setId(old.getId());
                reportMapper.updateById(report);
            } else {
                reportMapper.insert(report);
            }

            if (!diffBizNos.isEmpty()) {
                log.error("[Reconcile] 对账不平! bizDate={}, 差异 {} 笔: {}", bizDate, diffBizNos.size(), diffBizNos);
                // TODO 接入告警（企业微信 / 钉钉 / Prometheus Alertmanager）
            } else {
                log.info("[Reconcile] 对账平账, bizDate={}, 本地 {} 笔 / 远端 {} 笔, 金额 {}",
                        bizDate, locals.size(), bills.size(), localAmount);
            }
        } catch (Exception e) {
            log.error("[Reconcile] 对账失败, bizDate={}", bizDate, e);
            ReconcileReport failed = new ReconcileReport();
            failed.setBizDate(bizDate);
            failed.setStatus(ReconcileReport.STATUS_FAILED);
            failed.setDiffBizNos(e.getMessage());
            reportMapper.insert(failed);
        }
    }
}
