package io.github.starks.pixelnsg;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

public final class ForcingModule extends XposedModule {
    private static final String TAG = "PixelBandLock";
    private static final String NSG = "com.qtrun.QuickTest";
    private static final long NSG_VERSION_CODE = 4081430;
    private static final String ROW_KEY = "starks_pixel11_band_lock";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!NSG.equals(param.getPackageName()) || !"kodiak".equals(Build.DEVICE)) return;
        ClassLoader loader = param.getClassLoader();
        try {
            // These names are from the qtrun 4.8.14 build, including the obfuscated AndroidX classes.
            Method createPreferences = loader.loadClass("o90").getDeclaredMethod("f0", String.class);
            Method getActivity = loader.loadClass("os").getDeclaredMethod("m");
            Method findPreference = loader.loadClass("if0").getDeclaredMethod("e0", String.class);
            getActivity.setAccessible(true);
            findPreference.setAccessible(true);
            hook(createPreferences).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object fragment = chain.getThisObject();
                    Activity activity = (Activity) getActivity.invoke(fragment);
                    if (activity != null && activity.getPackageManager().getPackageInfo(NSG, 0)
                            .getLongVersionCode() == NSG_VERSION_CODE) {
                        Object bandCategory = findPreference.invoke(fragment, "forcing_band_category");
                        if (bandCategory != null) addBandPreference(loader, activity, bandCategory);
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Could not add the Pixel band row", error);
                }
                return result;
            });
            Log.i(TAG, "Installed Forcing Band row hook");
        } catch (Exception error) {
            Log.w(TAG, "Forcing Band row hook unavailable", error);
        }
    }

    private static void addBandPreference(ClassLoader loader, Activity activity, Object category)
            throws ReflectiveOperationException {
        Class<?> preference = loader.loadClass("androidx.preference.Preference");
        Class<?> group = loader.loadClass("androidx.preference.PreferenceGroup");
        Class<?> managerType = loader.loadClass("nf0");
        Class<?> clickType = loader.loadClass("bf0");

        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) group.getField("P").get(category);
        Field key = preference.getField("l");
        for (Object child : children) {
            if (ROW_KEY.equals(key.get(child))) return;
        }

        Context themedContext = (Context) preference.getField("a").get(category);
        Object manager = preference.getField("b").get(category);
        if (manager == null) return;
        Object row = preference.getConstructor(Context.class, AttributeSet.class)
                .newInstance(themedContext, null);
        key.setAccessible(true);
        key.set(row, ROW_KEY);
        preference.getField("h").set(row, "Pixel 11 Band Lock");
        preference.getField("i").set(row, "LTE and 5G NR · SIM 1");
        Object onClick = Proxy.newProxyInstance(loader, new Class<?>[]{clickType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == clickType) {
                BandDialog.open(activity);
                return null;
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> ROW_KEY;
            };
        });
        preference.getField("f").set(row, onClick);
        preference.getMethod("l", managerType).invoke(row, manager);
        preference.getField("I").set(row, category);
        children.add(row);
        Log.i(TAG, "Added Pixel 11 Band Lock under Band");
    }
}
