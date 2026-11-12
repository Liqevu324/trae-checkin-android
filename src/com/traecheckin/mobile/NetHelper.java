package com.traecheckin.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONObject;

/** 网络就绪预检与网络错误归类；不替代真实 HTTPS 请求。 */
public final class NetHelper {

    private NetHelper() {
    }

    /**
     * 系统是否报告当前网络可承载互联网请求。
     * 不把 VALIDATED 当成硬条件：部分国内 ROM 的网络探测不可达时，实际业务域名仍可访问。
     */
    public static boolean isConnected(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (SecurityException e) {
            // 权限被 ROM 异常限制时交给真实请求判断，避免预检误伤正常网络。
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 自动/手动签到前的短暂等待；只等待系统恢复网络，不伪造业务结果。 */
    public static boolean awaitNetwork(Context ctx, long maxWaitMs) {
        if (isConnected(ctx)) return true;
        long deadline = System.currentTimeMillis() + Math.max(0L, maxWaitMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isConnected(ctx)) return true;
        }
        return isConnected(ctx);
    }

    /** 引擎输出中的稳定网络错误标记。 */
    public static boolean isNetworkError(JSONObject result) {
        return result != null && result.optBoolean("network_error", false);
    }

    /** 兼容旧历史记录：旧版没有 error_type，只能由错误文案归类。 */
    public static boolean isNetworkMessage(String message) {
        return message != null && message.startsWith("网络错误");
    }
}
