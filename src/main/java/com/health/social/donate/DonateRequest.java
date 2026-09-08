package com.health.social.donate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 基金会打赏请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DonateRequest implements Serializable {

    private String bizNo;

    private Long userId;

    private Long projectId;

    private BigDecimal amount;
}
