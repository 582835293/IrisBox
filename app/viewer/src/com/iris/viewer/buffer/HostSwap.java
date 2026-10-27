package com.irisbox.viewer;

import org.json.JSONObject;
import java.io.IOException;

/** Fixed host controller and allowlisted sizes; no path comes from UI/ROM. */
final class HostSwap {
    static final int[] SIZES = {0,1024,2048,4096};
    static JSONObject run(String action,int mib,boolean automatic) throws Exception {
        if (!"status".equals(action) && !"configure".equals(action)) throw new IOException("Invalid swap action");
        boolean valid=false; for(int size:SIZES) if(size==mib) valid=true;
        if (!valid) throw new IOException("Invalid swap size");
        String command="test \"$(cat /data/irisbox/runtime/engine.version)\" = "+RootShell.quote(EngineInstaller.VERSION)+"\n"+
            "/system/bin/sh /data/irisbox/runtime/host_swap.sh "+action;
        if("configure".equals(action)) command+=" "+mib+" "+(automatic?"1":"0");
        String output=RootShell.run(command,"configure".equals(action)?300:15);
        for(String line:output.split("\\r?\\n")) if(line.startsWith("IRIS_SWAP_JSON=")) return new JSONObject(line.substring(15));
        throw new IOException(I18n.known("未收到交换空间状态，请刷新检查"));
    }
    static String error(String text) {
        String value=text==null?"":text;
        String[] codes={"STOP_INSTANCES","START_IN_PROGRESS","DISK_SPACE","RECLAIM_MEMORY","UNSAFE_PATH","FILE_IDENTITY","FILE_RECEIPT_MISSING","SETTINGS_INVALID","FOREIGN_BOOT_HOOK","UNSAFE_BOOT_HOOK","ENABLE_FAILED","DISABLE_FAILED","ALLOCATE_FAILED","FORMAT_FAILED","ENGINE_MAINTENANCE"};
        String[] messages={"请先停止所有虹膜空间实例，再更改交换空间","实例正在启动，请稍后重试","磁盘空间不足，需要保留至少2GB余量","可用内存不足以安全停用交换空间，请先释放宿主内存","交换文件路径不安全，已停止操作","交换文件身份校验失败，保留现场等待维护","交换文件缺少维护记录，已停止操作","交换空间设置损坏，保留现场等待维护","开机脚本名称已被其他内容占用，未覆盖","开机脚本路径不安全，已停止操作","无法启用交换空间，请检查内核和存储支持","未能停用交换空间，请刷新查看实际状态","无法分配交换文件，请检查存储空间","无法初始化交换文件，请检查存储支持","引擎维护尚未完成，请先恢复维护"};
        for(int i=0;i<codes.length;i++) if(value.contains("IRIS_SWAP_ERROR="+codes[i])) return I18n.known(messages[i]);
        return I18n.known("交换空间操作失败，请确认宿主Root授权及引擎版本")+"\n"+I18n.diagnostic(value);
    }
}
