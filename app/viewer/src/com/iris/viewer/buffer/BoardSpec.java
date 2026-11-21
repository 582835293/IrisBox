package com.irisbox.viewer;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 宿主板卡 Profile 描述模型（由 assets/boards.json 加载）。
 * 将板卡设备号、指纹、API 等级、内存配额与 ROM 校验清单索引统一起来，避免代码内硬编码单板常量。
 */
final class BoardSpec {
    final String profile;
    final String displayName;
    final String displayNameEn;
    final List<String> devices;
    final String abi;
    final int androidApi;
    final List<String> fingerprints;
    final int minMiB;
    final int maxMiB;
    final int defaultMiB;
    final boolean requireExternalMount;

    private BoardSpec(JSONObject json) throws Exception {
        this.profile = json.getString("profile");
        this.displayName = json.getString("displayName");
        this.displayNameEn = json.optString("displayNameEn", this.displayName);
        JSONObject host = json.getJSONObject("host");
        this.devices = stringList(host.getJSONArray("devices"));
        this.abi = host.getString("abi");
        this.androidApi = host.getInt("androidApi");
        this.fingerprints = stringList(host.getJSONArray("fingerprints"));
        this.requireExternalMount = host.optBoolean("requireExternalMount", false);
        JSONObject mem = json.getJSONObject("guest").getJSONObject("memory");
        this.minMiB = mem.getInt("minMiB");
        this.maxMiB = mem.getInt("maxMiB");
        this.defaultMiB = mem.getInt("defaultMiB");
    }

    private static List<String> stringList(JSONArray array) throws Exception {
        ArrayList<String> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) list.add(array.getString(i));
        return list;
    }

    static List<BoardSpec> loadAll(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("boards.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            JSONArray arr = root.getJSONArray("boards");
            ArrayList<BoardSpec> result = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item.optBoolean("verified", false)) result.add(new BoardSpec(item));
            }
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法解析板卡配置 boards.json", e);
        }
    }

    static BoardSpec matchHost(Context c) throws IOException {
        String hostAbi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        for (BoardSpec spec : loadAll(c)) {
            if (spec.devices.contains(Build.DEVICE)
                && spec.abi.equals(hostAbi)
                && spec.androidApi == Build.VERSION.SDK_INT
                && spec.fingerprints.contains(Build.FINGERPRINT)) {
                return spec;
            }
        }
        throw new IOException("当前宿主设备尚未适配（device=" + Build.DEVICE + ", sdk=" + Build.VERSION.SDK_INT + "）");
    }

    static boolean isExternalMountRequired(Context c) {
        try {
            return matchHost(c).requireExternalMount;
        } catch (IOException ignored) {
            return false;
        }
    }

    String romProfileAssetPath() {
        return "rom-profiles/" + profile + ".json";
    }

    boolean matchesRomManifest(JSONObject manifest) {
        return manifest.optInt("formatVersion", 0) == 1
            && profile.equals(manifest.optString("compatibility", ""))
            && abi.equals(manifest.optString("abi", ""))
            && androidApi == manifest.optInt("androidApi", 0)
            && fingerprints.contains(manifest.optString("hostFingerprint", ""));
    }
}
