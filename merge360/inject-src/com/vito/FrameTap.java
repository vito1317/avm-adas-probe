package com.vito;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 注入到原廠 360 App 的掛鉤。做兩件事：
 *   1) bowl view 一開（start）→ 把我們 App 的 OverlayService 叫起來。
 *   2) 原廠建相機 session 時（addSurface）→ 同步向我們 App 要一個 Surface，加進 surface 清單，
 *      相機一次開、原廠 bowl 跟我們同時拿同一份影格。
 *
 * 跟我們 App 溝通全部走 ContentResolver.call（content://com.vito.avmprobe.frameshare）：
 *   enabled → 開關；surface → 拿 Surface（同步，不必等 bindService）；log → 回報這邊做了什麼，
 *   顯示在我們 App 的主頁與 3D 面板上（車機常常沒 adb）。
 *   provider 拿不到 Surface 時才退回舊的 Binder 路線。
 *
 * 安全第一：
 *   - 開關預設關；問不到我們 App（沒裝、舊版）＝關。關閉時 addSurface 什麼都不做。
 *   - 全程 try/catch。任何失敗都當作沒這回事，原封不動用原廠的 surface 清單。
 */
public final class FrameTap {
    private static final String TAG = "FrameTap";
    private static final Uri PROVIDER = Uri.parse("content://com.vito.avmprobe.frameshare");
    private static final String PKG = "com.vito.avmprobe";
    private static final String SVC = "com.vito.avmprobe.OverlayService";
    // 必須跟 OverlayService.TRANSACT_GET_SURFACE 一致（IBinder.FIRST_CALL_TRANSACTION + 0）
    private static final int TRANSACT_GET_SURFACE = IBinder.FIRST_CALL_TRANSACTION;

    private static volatile IBinder sBinder;
    private static volatile boolean sBinding;
    private static ServiceConnection sConn;
    private static volatile Context sCtx;
    /** 我們交出去的 Surface（同一個物件重複用，原廠的 contains 檢查才認得出來）。 */
    private static volatile Surface sSurface;
    /** 原廠建立過的相機物件（I360CameraInterface），由建構子裡的掛鉤登記。 */
    private static final List<WeakReference<Object>> sCams = new ArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** 原廠相機的擷取尺寸（它截圖的 ImageReader 也用這個），從相機物件讀。 */
    private static volatile int sW = 1920, sH = 1080;

    /**
     * 合併版：我們的引擎（com.vito.avmprobe.Engine360）直接編在這個 APK 裡 → 同 process 直接呼叫。
     * 找不到就是 B 模式（兩個 App），改走我們 App 的 ContentProvider。
     */
    private static final String ENGINE = "com.vito.avmprobe.Engine360";
    private static volatile boolean sEngineChecked;
    private static volatile Class<?> sEngine;

    private static Class<?> engine() {
        if (!sEngineChecked) {
            try { sEngine = Class.forName(ENGINE); } catch (Throwable t) { sEngine = null; }
            sEngineChecked = true;
        }
        return sEngine;
    }

    private static Object engineCall(String name, Class<?>[] types, Object... args) throws Exception {
        return engine().getMethod(name, types).invoke(null, args);
    }

    private FrameTap() {}

    private static Context ctx() {
        Context c = sCtx;
        if (c == null) {
            // 相機 session 可能比掛鉤的 activity 先建 → 直接拿這個 process 的 Application
            try {
                c = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                sCtx = c;
            } catch (Throwable ignore) {}
        }
        return c;
    }

    private static Bundle call(String method, String arg) {
        Context c = ctx();
        if (c == null) return null;
        try {
            return c.getContentResolver().call(PROVIDER, method, arg, null);
        } catch (Throwable t) {
            return null;   // 我們的 App 沒裝 / 舊版沒有 provider
        }
    }

    /** 回報給我們 App（顯示在它的主頁與 3D 面板），同時寫 logcat。 */
    private static void report(String msg) {
        Log.i(TAG, msg);
        try {
            if (engine() != null) engineCall("log", new Class<?>[]{String.class}, msg);
            else call("log", msg);
        } catch (Throwable ignore) {}
    }

    private static boolean enabled() {
        if (engine() != null) {
            Context c = ctx();
            if (c == null) return false;
            try { return (Boolean) engineCall("enabled", new Class<?>[]{Context.class}, c); }
            catch (Throwable t) { return false; }
        }
        Bundle b = call("enabled", null);
        return b != null && b.getBoolean("enabled", false);
    }

    /** bowl view 啟動時呼叫：把我們的 App 叫起來並綁定。 */
    public static void start(Activity act) {
        try {
            if (act == null) return;
            sCtx = act.getApplicationContext();
            installCrashHandler(sCtx);
            if (engine() != null) {
                // 合併版：不管開關都掛上標注圖層與「ADAS」鈕（關著也要能從那顆鈕打開）
                try { engineCall("attach", new Class<?>[]{Activity.class}, act); }
                catch (Throwable t) { Log.e(TAG, "Engine360.attach 失敗：" + t); }
            }
            if (!enabled()) {
                // 開關關了：之前加過的 Surface 拿掉，原廠回到原樣
                sMain.postDelayed(new Runnable() { public void run() { detachAll(); } }, 1500);
                return;
            }
            report("掛鉤觸發：" + act.getClass().getSimpleName() + "，相機物件 " + liveCams().size() + " 個");
            if (engine() == null) bind(sCtx);   // B 模式才要叫起另一個 App 的服務
            // 原廠相機多半早就開好、不會重建 session → 用它的 addOutputSurface 在運作中加輸出
            // （它截圖也是這樣）。冷開機時原廠相機還在開，這時去「停相機→加輸出→重開」會跟原廠
            // 自己的啟動撞在一起 → 等相機**穩定運作 3 秒**才接。
            if (!sWaiting) { sWaiting = true; waitStableThenAttach(0, 0); }   // 一次啟動會進來好幾次，只排一個
            if (engine() == null) {
                try { Toast.makeText(act, "影格共用：已啟動偵測疊圖", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "start 失敗（不影響原廠）：" + t);
        }
    }

    private static void bind(Context ctx) {
        if (sBinder != null || sBinding) return;
        sBinding = true;
        try {
            Intent it = new Intent();
            it.setComponent(new ComponentName(PKG, SVC));
            try { ctx.startForegroundService(it); } catch (Throwable t) {
                try { ctx.startService(it); } catch (Throwable ignore) {}
            }
            sConn = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder service) {
                    sBinder = service;
                    Log.i(TAG, "OverlayService 綁上了");
                }
                public void onServiceDisconnected(ComponentName name) {
                    sBinder = null; sBinding = false;
                }
            };
            boolean ok = ctx.bindService(it, sConn, Context.BIND_AUTO_CREATE);
            if (!ok) { sBinding = false; report("bindService 回 false"); }
        } catch (Throwable t) {
            sBinding = false;
            report("bind 失敗：" + t);
        }
    }

    /**
     * 注入在原廠切換視角（nativesetBvState）之前：把數字換成視角名稱（BV_state 列舉），
     * 告訴合併版引擎。B 模式（沒有引擎）不用。
     */
    public static void onBvState(int v) {
        try {
            if (engine() == null) return;
            String name = null;
            try {
                Class<?> st = Class.forName("com.baony.birdview.BVDisplayManager$BV_state");
                Method gv = st.getMethod("getValue");
                for (Object e : st.getEnumConstants()) {
                    if (((Integer) gv.invoke(e)) == v) { name = ((Enum<?>) e).name(); break; }
                }
            } catch (Throwable ignore) {}
            engineCall("onViewState", new Class<?>[]{int.class, String.class}, v, name != null ? name : ("STATE_" + v));
        } catch (Throwable t) {
            Log.e(TAG, "onBvState 失敗：" + t);
        }
    }

    private static final int STABLE_TICKS = 3;     // 連續幾秒都在擷取才算穩定
    private static final int MAX_TICKS = 40;       // 最多等這麼多秒

    private static volatile boolean sWaiting;

    private static void waitStableThenAttach(final int tick, final int stable) {
        sMain.postDelayed(new Runnable() {
            public void run() {
                try {
                    boolean capturing = false;
                    for (Object cam : liveCams()) {
                        try {
                            Method m = findMethod(cam.getClass(), "isCapturing");
                            if (m != null && (Boolean) m.invoke(cam)) { capturing = true; break; }
                        } catch (Throwable ignore) {}
                    }
                    int st = capturing ? stable + 1 : 0;
                    if (st >= STABLE_TICKS) { sWaiting = false; attachAll(); return; }
                    if (tick >= MAX_TICKS) {
                        // 等不到在擷取：照樣加進清單（相機沒在跑時 addOutputSurface 只是記下來，不會停／開相機）
                        report("等了 " + MAX_TICKS + " 秒相機都沒在擷取，直接登記輸出");
                        sWaiting = false;
                        attachAll();
                        return;
                    }
                    waitStableThenAttach(tick + 1, st);
                } catch (Throwable t) {
                    sWaiting = false;
                    report("waitStable 失敗：" + t);
                }
            }
        }, 1000);
    }

    private static Method findMethod(Class<?> k, String name, Class<?>... types) {
        while (k != null) {
            try { Method m = k.getDeclaredMethod(name, types); m.setAccessible(true); return m; }
            catch (NoSuchMethodException e) { k = k.getSuperclass(); }
        }
        return null;
    }

    private static volatile boolean sCrashHandler;

    /**
     * 原廠 process 閃退時把堆疊交給引擎（存檔、判斷是不是我們害的），再交回原本的處理器（照常閃退）。
     * 下次開 360 可以在 ADAS 設定最上面看到原因，不用 adb。
     */
    private static void installCrashHandler(final Context ctx) {
        if (sCrashHandler) return;
        sCrashHandler = true;
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread th, Throwable e) {
                    try {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        e.printStackTrace(new java.io.PrintWriter(sw));
                        String trace = "thread=" + th.getName() + "\n" + sw;
                        if (engine() != null) engineCall("onCrash", new Class<?>[]{Context.class, String.class}, ctx, trace);
                    } catch (Throwable ignore) {}
                    if (prev != null) prev.uncaughtException(th, e);
                }
            });
        } catch (Throwable ignore) {}
    }

    /** 注入在 I360CameraInterface 建構子裡：登記原廠的相機物件。 */
    public static void register(Object cam) {
        try {
            synchronized (sCams) { sCams.add(new WeakReference<>(cam)); }
            Log.i(TAG, "登記相機物件 " + cam.getClass().getSimpleName());
        } catch (Throwable ignore) {}
    }

    private static List<Object> liveCams() {
        List<Object> out = new ArrayList<>();
        synchronized (sCams) {
            for (int i = sCams.size() - 1; i >= 0; i--) {
                Object c = sCams.get(i).get();
                if (c == null) sCams.remove(i); else out.add(c);
            }
        }
        return out;
    }

    /** 我們的 Surface：快取的還有效就用，失效（我們 App 重啟過）就重拿。 */
    private static Surface ourSurface() {
        Surface s = sSurface;
        if (s != null && s.isValid()) return s;
        Surface n = null;
        if (engine() != null) {
            try { n = (Surface) engineCall("surface", new Class<?>[]{int.class, int.class}, sW, sH); }
            catch (Throwable t) { report("Engine360.surface 失敗：" + t); }
            if (n != null && n.isValid()) { sSurface = n; return n; }
            return null;
        }
        Bundle b = call("surface", sW + "x" + sH);
        if (b != null) {
            b.setClassLoader(Surface.class.getClassLoader());
            n = b.getParcelable("surface");
        }
        if (n == null && sBinder != null) n = fetchSurface(sBinder);
        if (n != null && n.isValid()) { sSurface = n; return n; }
        return null;
    }

    /** 用原廠相機物件的 getCaptureSize()（它截圖的 ImageReader 就是這個尺寸，HAL 一定支援）。 */
    private static void readCaptureSize(Object cam) {
        try {
            Method m = null;
            Class<?> k = cam.getClass();
            while (k != null && m == null) {
                try { m = k.getDeclaredMethod("getCaptureSize"); } catch (NoSuchMethodException e) { k = k.getSuperclass(); }
            }
            if (m == null) return;
            m.setAccessible(true);
            android.util.Size sz = (android.util.Size) m.invoke(cam);
            if (sz != null && sz.getWidth() > 0 && sz.getHeight() > 0) { sW = sz.getWidth(); sH = sz.getHeight(); }
        } catch (Throwable ignore) {}
    }

    @SuppressWarnings("unchecked")
    private static List<Object> targets(Object cam) throws Exception {
        Class<?> k = cam.getClass();
        while (k != null) {
            try {
                Field f = k.getDeclaredField("mTargetSurface");
                f.setAccessible(true);
                return (List<Object>) f.get(cam);
            } catch (NoSuchFieldException e) { k = k.getSuperclass(); }
        }
        return null;
    }

    private static void invoke(Object cam, String name, Surface s) throws Exception {
        Method m = null;
        Class<?> k = cam.getClass();
        while (k != null && m == null) {
            try { m = k.getDeclaredMethod(name, Surface.class); } catch (NoSuchMethodException e) { k = k.getSuperclass(); }
        }
        if (m == null) throw new NoSuchMethodException(name);
        m.setAccessible(true);
        m.invoke(cam, s);
    }

    /** 把原廠清單裡失效的 Surface（我們以前給的）拿掉。 */
    private static void dropDead(Object cam) throws Exception {
        List<Object> t = targets(cam);
        if (t == null) return;
        List<Surface> dead = new ArrayList<>();
        for (Object o : new ArrayList<>(t)) if (o instanceof Surface && !((Surface) o).isValid()) dead.add((Surface) o);
        for (Surface d : dead) { invoke(cam, "removeOutputSurface", d); report("移除失效的 Surface"); }
    }

    private static void attachAll() {
        try {
            if (!enabled()) return;
            List<Object> cams = liveCams();
            if (cams.isEmpty()) { report("沒有登記到原廠相機物件"); return; }
            readCaptureSize(cams.get(0));
            Surface s = ourSurface();
            if (s == null) { report("拿不到我們的 Surface，這次不加"); return; }
            for (Object cam : cams) {
                try {
                    dropDead(cam);
                    List<Object> t = targets(cam);
                    if (t != null && t.contains(s)) { report(cam.getClass().getSimpleName() + " 已經有我們的 Surface"); continue; }
                    int before = t == null ? -1 : t.size();
                    invoke(cam, "addOutputSurface", s);
                    report("addOutputSurface → " + cam.getClass().getSimpleName() + "（輸出 " + before + " → "
                            + (t == null ? -1 : t.size()) + "）");
                } catch (Throwable e) {
                    report("addOutputSurface 失敗（" + cam.getClass().getSimpleName() + "）：" + e);
                }
            }
        } catch (Throwable t) {
            report("attachAll 失敗：" + t);
        }
    }

    private static void detachAll() {
        try {
            Surface s = sSurface;
            for (Object cam : liveCams()) {
                try {
                    dropDead(cam);
                    List<Object> t = targets(cam);
                    if (s != null && t != null && t.contains(s)) {
                        invoke(cam, "removeOutputSurface", s);
                        Log.i(TAG, "B 模式關閉，已移除我們的 Surface");
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 原廠建 capture session 前呼叫，把我們的 Surface 加進清單。
     * 開關關、拿不到 Surface、或任何例外 → 清單原封不動（原廠相機照常）。
     */
    public static void addSurface(List<Surface> surfaces) {
        try {
            if (surfaces == null || !enabled()) return;
            String who = caller();
            int before = surfaces.size();
            Surface s = ourSurface();
            if (s != null && surfaces.contains(s)) return;   // 已經靠 addOutputSurface 加進來了
            if (s == null) {
                report("建相機 session（" + who + "，原本 " + before + " 個輸出）但拿不到我們的 Surface");
                return;
            }
            surfaces.add(s);
            report("已加入我們的 Surface（" + who + "，原本 " + before + " 個輸出 → " + surfaces.size() + "）");
        } catch (Throwable t) {
            report("addSurface 失敗（原廠照常）：" + t);
        }
    }

    /** 哪個原廠類別在建 session（Camera2Instance 有好幾個變體，看是哪一個）。 */
    private static String caller() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            if (st.length > 2) {
                String c = st[2].getClassName();
                return c.substring(c.lastIndexOf('.') + 1) + "." + st[2].getMethodName();
            }
        } catch (Throwable ignore) {}
        return "?";
    }

    private static Surface fetchSurface(IBinder b) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            b.transact(TRANSACT_GET_SURFACE, data, reply, 0);
            if (reply.readInt() == 0) return null;
            return Surface.CREATOR.createFromParcel(reply);
        } catch (Throwable t) {
            Log.e(TAG, "transact 失敗：" + t);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
