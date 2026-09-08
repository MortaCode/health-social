package com.health.social.donate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.common.BizException;
import com.health.social.entity.DonateLocalRecord;
import com.health.social.entity.DonateRecord;
import com.health.social.mapper.DonateLocalRecordMapper;
import com.health.social.mapper.DonateRecordMapper;
import com.health.social.mapper.UserWalletMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 公益打赏服务 —— 基于<b>本地事务表</b>（本地消息表）的最终一致性实现。
 *
 * <h3>为什么需要本地事务表</h3>
 * <p>"扣用户余额"是本地事务，"通知基金会捐款"是跨网络调用，
 * 两者无法放进同一个数据库事务，也无法使用 XA（性能与可用性都不可接受）。
 *
 * <p>本地消息表模式把"业务数据"与"待发送消息"放进<b>同一个本地事务</b>：
 * <pre>
 *   BEGIN
 *     1. UPDATE t_user_wallet SET balance = balance - x WHERE user_id=? AND balance >= x   (本地)
 *     2. INSERT t_donate_record      (status = INIT)                                       (本地)
 *     3. INSERT t_donate_local_record(status = PENDING)                                    (本地)
 *   COMMIT   ← 到这一步，业务与"待办事项"必然同时成功或同时失败
 *
 *   afterCommit → 4. 调用基金会（最多重试 N 次，指数退避）
 *                 5. 成功：本地记录置 SUCCESS，业务单置 CONFIRMED
 *                    终态失败：冲正退款，业务单置 CLOSED
 *   T+1 02:30 → 6. 与基金会日账单对账，兜底一切漏网之鱼
 * </pre>
 *
 * <p>对比 MQ 事务消息：本地事务表不依赖 MQ 的半消息能力，实现简单、可观测性更好，
 * 代价是数据库多一次插入 —— 打赏是低频写场景，这个代价完全可以接受。
 */
@Slf4j
@Service
public class DonateService {

    private final UserWalletMapper walletMapper;
    private final DonateRecordMapper donateRecordMapper;
    private final DonateLocalRecordMapper localRecordMapper;
    private final FoundationClient foundationClient;

    @Value("${health.donate.retry-limit:5}")
    private int retryLimit;

    public DonateService(UserWalletMapper walletMapper,
                         DonateRecordMapper donateRecordMapper,
                         DonateLocalRecordMapper localRecordMapper,
                         FoundationClient foundationClient) {
        this.walletMapper = walletMapper;
        this.donateRecordMapper = donateRecordMapper;
        this.localRecordMapper = localRecordMapper;
        this.foundationClient = foundationClient;
    }

    /**
     * 打赏下单
     *
     * @param idempotentKey 幂等号（客户端生成或服务端按业务键生成），重复提交只扣一次
     */
    @Transactional(rollbackFor = Exception.class)
    public DonateResult donate(long userId, long projectId, BigDecimal amount, String idempotentKey) {
        Objects.requireNonNull(idempotentKey, "幂等号不能为空");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("打赏金额必须大于 0");
        }

        // ---------- 1. 幂等：幂等号命中直接返回历史结果 ----------
        DonateRecord exist = donateRecordMapper.selectOne(
                new LambdaQueryWrapper<DonateRecord>().eq(DonateRecord::getBizNo, idempotentKey));
        if (exist != null) {
            return new DonateResult(exist.getId(), exist.getStatus(), true);
        }

        // ---------- 2. 扣款（条件更新，余额不足影响行数为 0） ----------
        int affected = walletMapper.deduct(userId, amount);
        if (affected == 0) {
            throw new BizException("余额不足");
        }

        // ---------- 3. 业务单 + 本地事务表（同一事务） ----------
        DonateRecord record = new DonateRecord();
        record.setId(IdWorker.getId());
        record.setBizNo(idempotentKey);
        record.setUserId(userId);
        record.setProjectId(projectId);
        record.setAmount(amount);
        record.setStatus(DonateRecord.STATUS_INIT);
        record.setTxNo("");
        donateRecordMapper.insert(record);

        DonateLocalRecord local = new DonateLocalRecord();
        local.setId(IdWorker.getId());
        local.setBizNo(idempotentKey);
        local.setDonateId(record.getId());
        local.setUserId(userId);
        local.setProjectId(projectId);
        local.setAmount(amount);
        local.setStatus(DonateLocalRecord.STATUS_PENDING);
        local.setRetryCount(0);
        local.setNextRetryAt(LocalDateTime.now());
        local.setTxNo("");
        local.setLastError("");
        localRecordMapper.insert(local);

        // ---------- 4. 事务提交后再推送，避免"事务回滚但钱已经捐出去" ----------
        Long localId = local.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pushToFoundation(localId);
                }
            });
        } else {
            pushToFoundation(localId);
        }

        return new DonateResult(record.getId(), DonateRecord.STATUS_INIT, false);
    }

    /**
     * 推送基金会（可重入：幂等键为 bizNo，重复调用不会产生重复捐赠）
     */
    public void pushToFoundation(Long localRecordId) {
        DonateLocalRecord local = localRecordMapper.selectById(localRecordId);
        if (local == null || DonateLocalRecord.STATUS_SUCCESS.equals(local.getStatus())) {
            return;
        }
        try {
            DonateResponse resp = foundationClient.donate(new DonateRequest(
                    local.getBizNo(), local.getUserId(), local.getProjectId(), local.getAmount()));

            if (resp != null && resp.isSuccess()) {
                markSuccess(local, resp.getTxNo());
                log.info("[Donate] 推送成功, bizNo={}, txNo={}", local.getBizNo(), resp.getTxNo());
            } else {
                String err = resp == null ? "基金会返回空" : resp.getMessage();
                markFailed(local, err);
            }
        } catch (Exception e) {
            log.warn("[Donate] 推送失败, bizNo={}, err={}", local.getBizNo(), e.getMessage());
            markFailed(local, e.getMessage());
        }
    }

    private void markSuccess(DonateLocalRecord local, String txNo) {
        local.setStatus(DonateLocalRecord.STATUS_SUCCESS);
        local.setTxNo(txNo == null ? "" : txNo);
        local.setLastError("");
        localRecordMapper.updateById(local);

        donateRecordMapper.update(null, new LambdaUpdateWrapper<DonateRecord>()
                .eq(DonateRecord::getId, local.getDonateId())
                .eq(DonateRecord::getStatus, DonateRecord.STATUS_INIT)
                .set(DonateRecord::getStatus, DonateRecord.STATUS_CONFIRMED)
                .set(DonateRecord::getTxNo, local.getTxNo()));
    }

    private void markFailed(DonateLocalRecord local, String error) {
        int retry = local.getRetryCount() + 1;
        local.setRetryCount(retry);
        local.setLastError(error == null ? "" : error.substring(0, Math.min(500, error.length())));

        if (retry >= retryLimit) {
            // 终态失败：标记 DEAD 并冲正，把钱退回去
            local.setStatus(DonateLocalRecord.STATUS_DEAD);
            localRecordMapper.updateById(local);
            compensate(local);
            log.error("[Donate] 超过重试上限，已冲正退款, bizNo={}, retry={}", local.getBizNo(), retry);
        } else {
            // 指数退避：1, 2, 4, 8 ... 最多 30 分钟
            int backoffMinutes = Math.min(30, 1 << retry);
            local.setStatus(DonateLocalRecord.STATUS_PENDING);
            local.setNextRetryAt(LocalDateTime.now().plusMinutes(backoffMinutes));
            localRecordMapper.updateById(local);
        }
    }

    /**
     * 冲正：退款 + 业务单置为 CLOSED。
     * 注意用 status = INIT 做条件，避免把已经 CONFIRMED 的单子错误关闭。
     */
    @Transactional(rollbackFor = Exception.class)
    public void compensate(DonateLocalRecord local) {
        walletMapper.refund(local.getUserId(), local.getAmount());
        donateRecordMapper.update(null, new LambdaUpdateWrapper<DonateRecord>()
                .eq(DonateRecord::getId, local.getDonateId())
                .eq(DonateRecord::getStatus, DonateRecord.STATUS_INIT)
                .set(DonateRecord::getStatus, DonateRecord.STATUS_CLOSED));
        log.warn("[Donate] 冲正完成, bizNo={}, amount={}", local.getBizNo(), local.getAmount());
    }

    /**
     * 打赏结果
     */
    @Data
    public static class DonateResult {

        private final Long recordId;
        private final String status;
        /** true 表示命中幂等，本次未产生新的扣款 */
        private final boolean duplicated;

        public DonateResult(Long recordId, String status, boolean duplicated) {
            this.recordId = recordId;
            this.status = status;
            this.duplicated = duplicated;
        }
    }
}
