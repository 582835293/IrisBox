package com.irisbox.buffer.viewer.broker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayDeque;

/** 完整启动日志落盘；HTTP每次只取一小段，不把整个历史塞进每秒/state。
 * offset是UTF-8字节位置，由上一页next继续；切分不能破坏汉字/emoji。
 * 小窗摘要可以留尾部，但历史文件从第一行起保留，不以行数滚动丢弃。
 */
final class BootLogStore implements Closeable {
    final Path file;
    private final BufferedOutputStream out;
    private final ArrayDeque<String> tail=new ArrayDeque<>();
    BootLogStore(Path file)throws IOException {
        this.file=file;
        if(Files.isSymbolicLink(file))throw new IOException("Unexpected log link");
        if(Files.exists(file)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected log file");
        out=new BufferedOutputStream(new FileOutputStream(file.toFile(),true),8192);
        Files.setPosixFilePermissions(file,java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    }
    synchronized void append(String line)throws IOException {
        if(line.isEmpty())return;
        out.write((line+"\n").getBytes(StandardCharsets.UTF_8));tail.addLast(line);
        while(tail.size()>8)tail.removeFirst();
    }
    synchronized String[] summary(){return tail.toArray(new String[0]);}
    synchronized long size()throws IOException {out.flush();return Files.size(file);}
    synchronized Page page(long offset,int maximum)throws IOException {
        long total=size();if(offset<0||offset>total||maximum<4||maximum>8192)throw new IOException("Invalid log page");
        byte[] bytes=new byte[(int)Math.min(maximum,total-offset)];
        try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")){input.seek(offset);input.readFully(bytes);}
        int n=bytes.length;
        if(n>0&&offset+n<total) {
            int lead=n-1;while(lead>=0&&(bytes[lead]&0xc0)==0x80)--lead;
            if(lead>=0){int value=bytes[lead]&0xff;int count=value<0x80?1:value<0xe0?2:value<0xf0?3:4;if(lead+count>n)n=lead;}
        }
        return new Page(offset,offset+n,total,new String(bytes,0,n,StandardCharsets.UTF_8));
    }
    public synchronized void close()throws IOException {out.close();}
    static final class Page {
        final long offset,next,total;final String text;
        Page(long offset,long next,long total,String text){this.offset=offset;this.next=next;this.total=total;this.text=text;}
    }
}
