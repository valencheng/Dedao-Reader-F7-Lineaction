package com.chengfei.cliptest;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/** adb shell + app_process 直写剪贴板（免安装，shell 权限即可运行） */
public class ShellSet {
    public static void main(String[] args) throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object sysMain = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(sysMain);
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = args.length > 0 ? args[0] : "这是测试";
        cm.setPrimaryClip(ClipData.newPlainText("cliptest", text));
        System.out.println("CLIP_SET_OK: " + text);
    }
}
