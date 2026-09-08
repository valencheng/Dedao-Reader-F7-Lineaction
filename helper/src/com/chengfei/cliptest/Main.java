package com.chengfei.cliptest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;

/** 一次性测试工具：启动即向剪贴板写入一条测试文本后自动退出，用于验证划线同步服务。 */
public class Main extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("cliptest",
                "划线同步端到端测试 " + System.currentTimeMillis()));
        finish();
    }
}
