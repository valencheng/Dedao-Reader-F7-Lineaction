package com.chengfei.clipsync;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 配置驱动的上传器。全部行为来自 config.json（USB 推送，改完即时生效，无需重启）：
 *
 *   endpoint      接口完整地址（必填，为空时新文本进入待传队列）
 *   method        默认 POST
 *   token         可选，自动附加 Authorization: Bearer <token>（headers 里已给则不覆盖）
 *   headers       额外请求头
 *   body_template 请求体模板；{{content}} 替换为带引号的 JSON 字符串，
 *                 {{date}} 替换为 yyyy-MM-dd HH:mm，{{source}} 替换为 clip
 *   min_length / max_length   按长度过滤
 *   deep_read     是否允许焦点窗口深读（默认 true）
 *
 * 失败自动进入本地待传队列（内部存储，上限 256KB），配置好或网络恢复后自动补传。
 */
final class Uploader {

    private static final String TAG = "ClipSync";
    /** 默认模板对齐得到大脑 openapi resource/note/save：topic_id 不传即默认知识库 */
    static final String DEFAULT_TEMPLATE =
            "{\"note_type\": \"plain_text\", \"title\": \"划线 {{date}}\", \"content\": {{content}},"
                    + " \"tags\": [\"划线\"], \"client_request_id\": \"{{clip_id}}\"}";
    private static final int PENDING_CAP_BYTES = 256 * 1024;

    private final Context mCtx;
    private final HandlerThread mThread;
    private final Handler mWorker;

    private final File mConfigExt;   // /sdcard/Android/data/<pkg>/files/config.json（USB 推送）
    private final File mConfigInt;   // 内部存储兜底
    private final File mStateFile;
    private final File mPendingFile;
    private long mCfgMtime = -1;
    private boolean mAssetLoaded;

    private final Object mCfgLock = new Object();
    private volatile String mEndpoint = "";
    private volatile String mMethod = "POST";
    private volatile String mToken = "";
    private volatile String mTokenPrefix = "Bearer";
    private volatile String mTemplate = DEFAULT_TEMPLATE;
    private volatile Map<String, String> mHeaders = new HashMap<String, String>();
    private volatile int mMinLen = 1;
    private volatile int mMaxLen = 200000;
    private volatile boolean mDeepRead = true;

    Uploader(Context ctx) {
        mCtx = ctx.getApplicationContext();
        File ext = null;
        try {
            ext = mCtx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (ext != null) {
            mConfigExt = new File(ext, "config.json");
        } else {
            mConfigExt = new File("/sdcard/Android/data/" + mCtx.getPackageName() + "/files/config.json");
        }
        mConfigInt = new File(mCtx.getFilesDir(), "config.json");
        mStateFile = new File(mCtx.getFilesDir(), "state.txt");
        mPendingFile = new File(mCtx.getFilesDir(), "pending.bin");
        mThread = new HandlerThread("clipsync-up");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
    }

    boolean deepReadEnabled() {
        reloadIfNeeded();
        return mDeepRead;
    }

    void enqueue(String text) {
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                doUpload(text);
            }
        });
    }

    void flushPending() {
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                doFlush();
            }
        });
    }

    void shutdown() {
        mThread.quitSafely();
    }

    // ------------------------------------------------------------ 上传

    private void doUpload(String text) {
        reloadIfNeeded();
        if (doUploadOnce(text)) {
            // 成功后补传历史积压（doFlush 内部直调 doUploadOnce，不会递归）
            mWorker.post(new Runnable() {
                @Override
                public void run() {
                    doFlush();
                }
            });
        } else {
            appendPending(text);
        }
    }

    /** @return true=成功（或按长度规则跳过）；false=失败待重试 */
    private boolean doUploadOnce(String text) {
        if (mEndpoint.isEmpty()) return false;
        if (text.length() < mMinLen || text.length() > mMaxLen) {
            Log.i(TAG, "skip by length rule (" + text.length() + " chars)");
            return true;
        }
        HttpURLConnection conn = null;
        try {
            long now = System.currentTimeMillis();
            String body = applyPlaceholders(mTemplate, text, now);

            conn = (HttpURLConnection) new URL(mEndpoint).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod(mMethod);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            Map<String, String> hs = mHeaders;
            for (Map.Entry<String, String> e : hs.entrySet()) {
                conn.setRequestProperty(e.getKey(), applyPlaceholders(e.getValue(), text, now));
            }
            if (!mToken.isEmpty() && conn.getRequestProperty("Authorization") == null) {
                conn.setRequestProperty("Authorization",
                        mTokenPrefix.isEmpty() ? mToken : mTokenPrefix + " " + mToken);
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(out);
            os.close();

            int code = conn.getResponseCode();
            String resp = readSmall(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 200 && code < 300) {
                Log.i(TAG, "upload ok: " + code + " " + resp);
                return true;
            }
            Log.w(TAG, "upload http " + code + " " + resp);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "upload error: " + t);
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void doFlush() {
        if (!mPendingFile.exists()) return;
        ArrayList<String> items = new ArrayList<String>();
        try (FileInputStream in = new FileInputStream(mPendingFile)) {
            byte[] all = readAll(in, PENDING_CAP_BYTES + 4096);
            int p = 0;
            while (p + 2 <= all.length) {
                int nl = indexOf(all, (byte) '\n', p);
                if (nl < 0) break;
                int len;
                try {
                    len = Integer.parseInt(new String(all, p, nl - p, StandardCharsets.US_ASCII).trim());
                } catch (NumberFormatException e) {
                    break;
                }
                if (len < 0 || nl + 1 + len > all.length) break;
                items.add(new String(all, nl + 1, len, StandardCharsets.UTF_8));
                p = nl + 1 + len;
            }
        } catch (Throwable t) {
            Log.w(TAG, "pending read error: " + t);
            return;
        }
        if (items.isEmpty()) {
            mPendingFile.delete();
            return;
        }
        Log.i(TAG, "flushing " + items.size() + " pending clip(s)");
        ArrayList<String> failed = new ArrayList<String>();
        for (String s : items) {
            reloadIfNeeded();
            if (!doUploadOnce(s)) failed.add(s);
        }
        rewritePending(failed);
    }

    // ------------------------------------------------------------ 待传队列

    private void appendPending(String text) {
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            long cur = mPendingFile.exists() ? mPendingFile.length() : 0;
            if (cur + body.length + 16 > PENDING_CAP_BYTES) {
                Log.w(TAG, "pending queue full, dropped");
                return;
            }
            FileOutputStream out = new FileOutputStream(mPendingFile, true);
            out.write(String.valueOf(body.length).getBytes(StandardCharsets.US_ASCII));
            out.write('\n');
            out.write(body);
            out.write('\n');
            out.close();
        } catch (Throwable t) {
            Log.w(TAG, "pending append error: " + t);
        }
    }

    private void rewritePending(ArrayList<String> items) {
        try {
            if (items.isEmpty()) {
                mPendingFile.delete();
                return;
            }
            FileOutputStream out = new FileOutputStream(mPendingFile);
            for (String s : items) {
                byte[] body = s.getBytes(StandardCharsets.UTF_8);
                out.write(String.valueOf(body.length).getBytes(StandardCharsets.US_ASCII));
                out.write('\n');
                out.write(body);
                out.write('\n');
            }
            out.close();
        } catch (Throwable t) {
            Log.w(TAG, "pending rewrite error: " + t);
        }
    }

    // ------------------------------------------------------------ 配置

    private File configFile() {
        return mConfigExt.exists() ? mConfigExt : mConfigInt;
    }

    private void reloadIfNeeded() {
        File f = null;
        try {
            f = configFile();
        } catch (Throwable ignored) {
        }
        if (f != null && f.exists()) {
            long mt = f.lastModified();
            synchronized (mCfgLock) {
                if (mt == mCfgMtime) return;
                mCfgMtime = mt;
            }
            byte[] buf = new byte[0];
            try (FileInputStream in = new FileInputStream(f)) {
                buf = readAll(in, 128 * 1024);
            } catch (Throwable t) {
                Log.e(TAG, "config read error: " + t);
            }
            applyConfig(buf);
            return;
        }
        // 没有推送过配置文件时，回退用打包进 APK 的 assets/config.json（只加载一次）
        if (mAssetLoaded) return;
        mAssetLoaded = true;
        byte[] buf;
        try (InputStream in = mCtx.getAssets().open("config.json")) {
            buf = readAll(in, 128 * 1024);
            Log.i(TAG, "using asset config");
        } catch (Throwable t) {
            Log.i(TAG, "no config yet (neither pushed nor in assets)");
            buf = new byte[0];
        }
        applyConfig(buf);
    }

    private void applyConfig(byte[] buf) {
        String endpoint = "", method = "POST", token = "", tokenPrefix = "Bearer", template = DEFAULT_TEMPLATE;
        Map<String, String> headers = new HashMap<String, String>();
        int minLen = 1, maxLen = 200000;
        boolean deep = true;
        try {
            if (buf.length > 0) {
                JSONObject cfg = new JSONObject(new String(buf, StandardCharsets.UTF_8));
                endpoint = cfg.optString("endpoint", "").trim();
                method = cfg.optString("method", "POST").trim().toUpperCase(Locale.US);
                token = cfg.optString("token", "").trim();
                tokenPrefix = cfg.optString("token_prefix", "Bearer").trim();
                template = cfg.optString("body_template", DEFAULT_TEMPLATE);
                minLen = cfg.optInt("min_length", 1);
                maxLen = cfg.optInt("max_length", 200000);
                deep = cfg.optBoolean("deep_read", true);
                JSONObject hs = cfg.optJSONObject("headers");
                if (hs != null) {
                    Iterator<String> it = hs.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        headers.put(k, hs.optString(k, ""));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "config parse error: " + t);
            endpoint = "";
        }
        synchronized (mCfgLock) {
            mEndpoint = endpoint;
            mMethod = method;
            mToken = token;
            mTokenPrefix = tokenPrefix;
            mTemplate = template;
            mHeaders = headers;
            mMinLen = minLen;
            mMaxLen = maxLen;
            mDeepRead = deep;
        }
        Log.i(TAG, "config loaded, endpoint=" + (endpoint.isEmpty() ? "(empty)" : endpoint)
                + " deepRead=" + deep);
    }

    // ------------------------------------------------------------ 状态与工具

    /** 读落盘状态：tsOut[0]=已处理剪贴板时间戳，hashOut[0]=已上传内容 SHA-1 */
    static void readState(Context ctx, long[] tsOut, String[] hashOut) {
        tsOut[0] = 0L;
        hashOut[0] = "";
        try {
            File f = new File(ctx.getFilesDir(), "state.txt");
            if (!f.exists()) return;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = readAll(in, 512);
                String s = new String(b, StandardCharsets.US_ASCII).trim();
                int nl = s.indexOf('\n');
                if (nl < 0) {
                    tsOut[0] = Long.parseLong(s);
                    return;
                }
                tsOut[0] = Long.parseLong(s.substring(0, nl).trim());
                hashOut[0] = s.substring(nl + 1).trim();
            }
        } catch (Throwable ignored) {
        }
    }

    static void writeState(Context ctx, long ts, String hash) {
        try {
            FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "state.txt"));
            out.write((ts + "\n" + hash).getBytes(StandardCharsets.US_ASCII));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    static String sha1hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }

    private static byte[] readAll(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > cap) break;
        }
        return bos.toByteArray();
    }

    private static int indexOf(byte[] a, byte v, int from) {
        for (int i = from; i < a.length; i++) {
            if (a[i] == v) return i;
        }
        return -1;
    }

    private static String readSmall(InputStream in) {
        if (in == null) return "";
        try {
            return new String(readAll(in, 4096), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 占位符替换。替换顺序有讲究：{{content}} 必须最后替换，
     * 这样划线正文里出现的 {{date}} 等字样只作为数据、不会被二次展开。
     * 请求头与请求体共用此方法（如 Idempotency 类头需要按内容取幂等键）。
     */
    private static String applyPlaceholders(String tpl, String text, long now) {
        return tpl.replace("{{date}}", fmtDate(now))
                .replace("{{source}}", "clip")
                .replace("{{clip_id}}", "clip-" + sha1hex(text))
                .replace("{{content}}", JSONObject.quote(text));
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }
}
