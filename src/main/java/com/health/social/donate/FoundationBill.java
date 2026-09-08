package com.health.social.donate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 基金会日账单条目（T+1 对账使用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FoundationBill implements Serializable {

    private String bizNo;

    private String txNo;

    private BigDecimal amount;

    /** CONFIRMED / REFUND ... */
    private String status;
}
