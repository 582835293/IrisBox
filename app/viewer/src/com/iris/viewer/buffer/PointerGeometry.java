package com.irisbox.viewer;

/** 纯坐标换算：nativeGeometry的19项快照 → 已完成显示矩形 → Guest逻辑坐标。
 * 黑边返回null，窗口/方向未同步时拒绝，不用View宽高直接当Guest尺寸。
 * 数组下标与nativeGeometry配套，字段变动必须同时修改两端和输入协议。
 * Pure geometry, shared by Android and the host-side regression test.
 */
final class PointerGeometry {
    /** 19项布局：0有效/1序号，2–5画面矩形，6–7View，8–9buffer，10–11逻辑尺寸；
     * 12–15 crop，16 transform，17事务计数，18 rotation。这里只接受已支持的完整裁剪。
     */
    static String rejection(long[] g, int viewWidth, int viewHeight, int inputWidth, int inputHeight) {
        if (g == null || g.length != 19 || g[0] != 1 || g[1] < 1) return "等待画面完成";
        if (g[6] != viewWidth || g[7] != viewHeight) return "等待窗口尺寸同步";
        if (!((g[10] == inputWidth && g[11] == inputHeight) || (g[10] == inputHeight && g[11] == inputWidth)) || g[18] < 0 || g[18] > 3) return "实例尺寸不匹配，输入已暂停";
        if (g[16] != 0 || g[12] != 0 || g[13] != 0 || g[14] != g[8] || g[15] != g[9]) return "当前旋转或裁剪尚不支持输入";
        if (g[8] < 1 || g[9] < 1 || g[2] < 0 || g[3] < 0 || g[4] > viewWidth || g[5] > viewHeight || g[2] >= g[4] || g[3] >= g[5]) return "画面位置无效";
        return "";
    }
    static float[] map(long[] g, int vw, int vh, int iw, int ih, float x, float y) {
        if (!rejection(g,vw,vh,iw,ih).isEmpty() || !Float.isFinite(x) || !Float.isFinite(y) || x < g[2] || x >= g[4] || y < g[3] || y >= g[5]) return null;
        return new float[]{Math.min(g[10]-1,(x-g[2])*g[10]/(g[4]-g[2])),Math.min(g[11]-1,(y-g[3])*g[11]/(g[5]-g[3]))};
    }
}
