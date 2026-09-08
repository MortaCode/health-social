package com.health.social.donate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 基金会打赏回执
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DonateResponse implements Serializable {

    private boolean success;

    /** 基金会流水号 */
    private String txNo;

    private String message;
}
