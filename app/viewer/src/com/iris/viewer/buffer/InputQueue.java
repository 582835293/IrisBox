package com.irisbox.viewer;

import java.util.ArrayDeque;
import java.util.Locale;

/** UI到InputLink的有界命令队列；只合并同按钮状态、同几何的连续可合并移动。
 * 按下/抬起不能被当普通MOVE丢掉；溢出放C取消，避免Guest留着按下状态。
 * R只清键盘，U只清指针，C清两者；修改字母协议须同步Guest InputProtocol。
 * Bounded queue. Coalesce movement only when the button state is identical.
 */
final class InputQueue {
    static final class Command {
        final char type; final float x,y,h,v; final int buttons; final String key;final boolean coalescible;
        int action,code,repeat,meta;int frameWidth,frameHeight,rotation=-1;
        Command(char type,float x,float y,int buttons,float h,float v,String key) { this(type,x,y,buttons,h,v,key,false); }
        Command(char type,float x,float y,int buttons,float h,float v,String key,boolean coalescible) { this.type=type;this.x=x;this.y=y;this.buttons=buttons;this.h=h;this.v=v;this.key=key;this.coalescible=coalescible; }
        static Command simple(char type) { return new Command(type,0,0,0,0,0,null); }
        static Command touchMode(boolean touch) { return new Command('T',0,0,touch?1:0,0,0,null); }
        static Command keyboard(int action,int code,int repeat,int meta) {
            Command command=simple('E');command.action=action;command.code=code;command.repeat=repeat;command.meta=meta;return command;
        }
        private String geometry() {return (rotation<0?"":" "+frameWidth+" "+frameHeight+" "+rotation)+"\n";}
        String line(long sequence) {
            switch(type) {
                case 'M': return String.format(Locale.US,"M %d %.3f %.3f %d",sequence,x,y,buttons)+geometry();
                case 'W': return String.format(Locale.US,"W %d %.3f %.3f %.3f %.3f",sequence,x,y,h,v)+geometry();
                case 'K': return "K "+sequence+" "+key+"\n";
                case 'T': return "T "+sequence+" "+buttons+"\n";
                case 'E': return "E "+sequence+" "+action+" "+code+" "+repeat+" "+meta+"\n";
                default: return type+" "+sequence+"\n";
            }
        }
    }
    final ArrayDeque<Command> items=new ArrayDeque<>();
    /** 仅MOVE可合并，按钮或几何变化保留；队列满清成C，让用户重新开始手势。
     */
    boolean offer(Command command) {
        if (command.type == 'C') { items.clear();items.add(command);return true; }
        // Focus loss removes only pending keyboard input, preserving pointer UP.
        if(command.type=='R')items.removeIf(item->item.type=='E'||item.type=='R');
        Command tail=items.peekLast();
        if(command.type=='M' && command.coalescible && tail!=null && tail.type=='M' && tail.coalescible && tail.buttons==command.buttons && tail.frameWidth==command.frameWidth && tail.frameHeight==command.frameHeight && tail.rotation==command.rotation) items.removeLast();
        if(items.size()>=32) { items.clear();items.add(Command.simple('C'));return false; }
        items.addLast(command);return true;
    }
    Command poll() { return items.pollFirst(); }
    void clear() { items.clear(); }
    int size() { return items.size(); }
}
