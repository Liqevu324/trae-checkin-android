package com.traecheckin.mobile;

/** 签到结果的纯 Java 判定规则，便于脱离 Android 运行时验证。 */
final class CheckinResult {

    private CheckinResult() {
    }

    /**
     * 签到模式必须最终确认已签到；只刷新模式取得有效状态后即可成功。
     *
     * @param doClaim 是否执行领取流程
     * @param checkedIn 服务端最终确认的当日签到状态
     * @return 本次操作是否成功
     */
    static boolean isSuccessful(boolean doClaim, boolean checkedIn) {
        return !doClaim || checkedIn;
    }

    /** claim 业务码为 0 才视为接口接受；是否真正到账由后续 status 回查确认。 */
    static boolean isClaimAccepted(int code, boolean hasCredits, boolean checkedIn) {
        return code == 0;
    }
}
