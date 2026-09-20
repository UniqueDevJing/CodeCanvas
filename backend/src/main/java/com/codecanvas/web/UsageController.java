package com.codecanvas.web;

import com.codecanvas.llm.TokenMeter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本台账接口。
 *
 * <p>单独开一个接口而不是塞进 /api/config：分级路由省了多少钱，是这个平台最该自证的一件事，
 * 值得能直接 curl 出来看，而不是埋在别的响应里。
 *
 * <p>返回里同时带上 {@code assumption} 与 {@code pricing}——反事实数字是算出来的，
 * 不写清前提就只是个好听的数。带上单价，别人可以拿自己的价目表重算一遍。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final TokenMeter meter;

    public UsageController(TokenMeter meter) {
        this.meter = meter;
    }

    @GetMapping
    public TokenMeter.Ledger usage() {
        return meter.ledger();
    }
}
