package com.canggo.zhishu.model;

import java.time.LocalDate;

public record DailyUsageStat(
        LocalDate recordDate,
        Long totalAmount,
        Long totalRequestCount
) {
}