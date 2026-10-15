package com.rootvmos.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

/** App-only locale. Guest locale, user filenames and raw logs are never rewritten. */
final class I18n {
    private static volatile Resources resources;
    static Context wrap(Context base) {
        String choice=base.getSharedPreferences("language",0).getString("choice","auto");
        if ("auto".equals(choice)) return base;
        Configuration config=new Configuration(base.getResources().getConfiguration());
        Locale locale="zh".equals(choice)?Locale.SIMPLIFIED_CHINESE:Locale.ENGLISH;
        config.setLocale(locale); config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }
    static void init(Context context) { resources=context.getResources(); }
    static String s(int id) {
        Resources current=resources;
        if(current==null)throw new IllegalStateException("App locale not initialized");
        return current.getString(id);
    }
    static String known(String text) {
        Integer id=LegacyText.IDS.get(text);
        return id==null?text:s(id);
    }
    static String diagnostic(String text) {
        if(text==null)return "";
        Integer exact=LegacyText.IDS.get(text);
        if(exact!=null)return s(exact);
        // Translate only an exception/progress prefix, preserving its filename suffix.
        String best="";
        for(String prefix:LegacyText.IDS.keySet())
            if(prefix.length()>best.length() && text.startsWith(prefix))best=prefix;
        return best.isEmpty()?text:s(LegacyText.IDS.get(best))+text.substring(best.length());
    }
    static String status(String text) {
        // Only fixed status headers emitted by our installer are translated.
        String[] lines=text.split("\n",-1);
        for(int i=0;i<lines.length;i++) {
            String line=lines[i]; lines[i]=known(line);
            for(String prefix:new String[]{"引擎：","新实例 ROM：","已安装 ROM："}) {
                if(line.startsWith(prefix)) {lines[i]=known(prefix)+line.substring(prefix.length());break;}
            }
        }
        return android.text.TextUtils.join("\n",lines);
    }
    static void choose(Activity activity) {
        String[] codes={"auto","en","zh"};
        String current=activity.getSharedPreferences("language",0).getString("choice","auto");
        int selected="en".equals(current)?1:"zh".equals(current)?2:0;
        new AlertDialog.Builder(activity).setTitle("Language / 语言")
            .setSingleChoiceItems(new String[]{"System default / 跟随系统","English","简体中文"},selected,(dialog,index)->{
                activity.getSharedPreferences("language",0).edit().putString("choice",codes[index]).commit();
                dialog.dismiss();activity.recreate();
            }).setNegativeButton(android.R.string.cancel,null).show();
    }
}
