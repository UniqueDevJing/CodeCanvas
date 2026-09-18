package com.codecanvas.common;

/**
 * 粗略 token 估算。
 *
 * <p>仅用于两处：① 上下文窗口预算控制（避免把提示词撑爆）② 厂商不返回用量时的成本估算兜底。
 * 中日韩字符按约 1 token/字，其余字符按约 4 字符/token 估算，误差在可接受范围内。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk + other / 4.0);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }
}
