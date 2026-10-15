package com.rootvmos.buffer.viewer.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 只读Host登记表：内部ID → 数据revision；账号/文件不保存在登记表里。
 * TreeMap按数字ID排序；界面number由当前排序位置算，永久ID及目录不变。
 * 重置换revision、删除去record；修改登记/数据统一交instances.sh，勿在此另写一套。
 * Small persistent registry. Guest accounts and files never live in this registry.
 */
final class InstanceCatalog {
    private static final Path ROOT=Paths.get("/data/rootvmos/runtime/config/instances_v1");
    static boolean valid(int id){return id>=1007&&id<=1134;}
    static SortedMap<Integer,String> all()throws IOException{return read(ROOT);}
    static SortedMap<Integer,String> read(Path root)throws IOException {
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(root.getParent())||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Instance registry unavailable");
        TreeMap<Integer,String> records=new TreeMap<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(root,"*.record")){
            for(Path file:stream){
                String name=file.getFileName().toString(),number=name.substring(0,name.length()-7);
                if(!number.matches("[1-9][0-9]{0,4}"))throw new IOException("Invalid instance record name");
                int id=Integer.parseInt(number);
                if(!valid(id)||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65)throw new IOException("Invalid instance record");
                String revision=new String(Files.readAllBytes(file),StandardCharsets.US_ASCII).trim();
                if(!revision.matches("[a-f0-9]{64}"))throw new IOException("Invalid instance revision");
                records.put(id,revision);
            }
        }
        return records;
    }
    /** Public ordinal comes from the current sorted catalog; IDs remain permanent. */
    /** headMap的条数+1产生连续显示序号，不改变内部ID、网段或数据目录。
     */
    static int number(SortedMap<Integer,String> records,int id)throws IOException {
        if(!valid(id)||!records.containsKey(id))throw new IOException("Instance was removed");
        return records.headMap(id).size()+1;
    }
    static String revision(int id)throws IOException {
        if(!valid(id))throw new IOException("Invalid instance");
        String value=all().get(id);if(value==null)throw new IOException("Instance was removed");return value;
    }
    static boolean contains(int id)throws IOException{return valid(id)&&all().containsKey(id);}
}
