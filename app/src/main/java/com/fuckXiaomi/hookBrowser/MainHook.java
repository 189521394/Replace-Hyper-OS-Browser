package com.fuckXiaomi.hookBrowser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    
    // 极简配置文件路径
    private static final String CONFIG_PATH = "/data/local/tmp/browser.txt";
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.xiaomi.aicr")) {
            return;
        }
        
        XposedBridge.log("成功注入小米AI识别进程！(纯净极简版)");
        String targetClass = "com.xiaomi.aicr.copydirect.util.SmartPasswordUtils";
        
        // ========================================================
        // 1. 核心拦截：替换 Intent 的目标包名
        // ========================================================
        XposedHelpers.findAndHookMethod(
                targetClass,
                lpparam.classLoader,
                "jumpToXiaoMiBrowser",
                Context.class,
                String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.args[0];
                        String str = (String) param.args[1];
                        
                        Intent intent = new Intent("android.intent.action.VIEW");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.putExtra("open_source", "clipboard_open");
                        
                        if (str.startsWith("http")) {
                            intent.setData(Uri.parse(str));
                        } else {
                            intent.setData(Uri.parse("https://" + str));
                        }
                        
                        // 动态读取你的配置文件
                        String userBrowserPkg = getCustomBrowserPkg();
                        if (userBrowserPkg != null && !userBrowserPkg.isEmpty()) {
                            intent.setPackage(userBrowserPkg);
                            XposedBridge.log("已强行注入目标浏览器: " + userBrowserPkg);
                        } else {
                            XposedBridge.log("未检测到配置文件，准备唤起系统选择器");
                        }
                        
                        context.startActivity(intent);
                        return null;
                    }
                }
        );
        
        // ========================================================
        // 2. 彻底斩断 Binder 死循环：拦截最底层的安装检查
        // ========================================================
        XposedHelpers.findAndHookMethod(
                targetClass,
                lpparam.classLoader,
                "isInstallForApp",
                Context.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String pkgName = (String) param.args[1];
                        if ("com.android.browser".equals(pkgName)) {
                            param.setResult(true);
                        }
                    }
                }
        );
        
        // ========================================================
        // 3. 在通知发送前的最后一刻，替换掉包裹里的图标！
        // ========================================================
        XposedHelpers.findAndHookMethod(
                "android.app.NotificationManager",
                lpparam.classLoader,
                "notify",
                int.class,
                android.app.Notification.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int id = (int) param.args[0];
                        android.app.Notification notification = (android.app.Notification) param.args[1];
                        
                        if (id == 111 && notification != null && notification.extras != null) {
                            Bundle extras = notification.extras;
                            
                            // 确认是复制网址的通知
                            if (extras.getString("copyText") != null) {
                                String customPkg = getCustomBrowserPkg();
                                if (customPkg == null || customPkg.isEmpty()) return;
                                
                                try {
                                    Context context = android.app.AndroidAppHelper.currentApplication();
                                    Icon newIcon = getCustomAppIcon(context, customPkg);
                                    
                                    // 暴力替换通知里的所有关键图标！
                                    XposedHelpers.setObjectField(notification, "mSmallIcon", newIcon);
                                    XposedHelpers.setObjectField(notification, "mLargeIcon", newIcon);
                                    
                                    // 替换小米特有的 Bundle 里的图标
                                    extras.putParcelable("miui.appIcon", newIcon);
                                    Bundle miuiFocusPics = extras.getBundle("miui.focus.pics");
                                    if (miuiFocusPics != null) {
                                        miuiFocusPics.putParcelable("miui.focus.pic_image", newIcon);
                                        miuiFocusPics.putParcelable("miui.land.pic_image", newIcon);
                                    }
                                    
                                    XposedBridge.log("成功替换通知栏悬浮窗图标为: " + customPkg);
                                } catch (Exception e) {
                                    XposedBridge.log("替换图标失败: " + e.getMessage());
                                }
                            }
                        }
                    }
                }
        );
    }
    
    // ========================================================
    // 工具方法区
    // ========================================================
    
    /**
     * 读取单行纯文本配置文件
     */
    private String getCustomBrowserPkg() {
        File configFile = new File(CONFIG_PATH);
        if (configFile.exists() && configFile.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            } catch (Exception e) {
                XposedBridge.log("读取配置文件失败: " + e.getMessage());
            }
        }
        return "";
    }
    
    /**
     * 获取第三方应用的 Icon 对象 (封装了 Drawable 转 Bitmap 的逻辑)
     */
    private Icon getCustomAppIcon(Context context, String pkgName) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        Drawable customAppIcon = pm.getApplicationIcon(pkgName);
        
        Bitmap bitmap;
        if (customAppIcon instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) customAppIcon).getBitmap();
        } else {
            // 兼容矢量图或自适应图标的绘制
            int width = Math.max(customAppIcon.getIntrinsicWidth(), 1);
            int height = Math.max(customAppIcon.getIntrinsicHeight(), 1);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            customAppIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            customAppIcon.draw(canvas);
        }
        return Icon.createWithBitmap(bitmap);
    }
}