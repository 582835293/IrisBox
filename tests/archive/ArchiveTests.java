package com.rootvmos.viewer;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;

/** 在Android实际org.json/ZIP实现上调用生产PackageArchive，不申请root。 */
public final class ArchiveTests extends Instrumentation {
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        int passed=0;StringBuilder failures=new StringBuilder();
        for(String name:new String[]{"valid","bad_hash","missing","duplicate","unknown","oversize","wrong_profile","traversal","absolute","wrong_first","manifest_bomb"}) {
            File dir=new File(getTargetContext().getCacheDir(),name);dir.mkdirs();boolean accepted=false;
            try(InputStream in=getTargetContext().getAssets().open(name+".zip")) {PackageArchive.extract(in,dir,"engine.json",false,message->{});accepted=true;}
            catch(Exception expected) { if(name.equals("valid"))failures.append(name).append(':').append(expected).append('\n'); }
            try {
                if(accepted!=name.equals("valid"))throw new Exception("accept="+accepted);
                if(new File(getTargetContext().getCacheDir(),"escaped").exists())throw new Exception("path escaped");
                passed++;
            }catch(Exception failure){failures.append(name).append(':').append(failure).append('\n');}
            try{PackageArchive.erase(dir);}catch(Exception failure){failures.append(failure);}
        }
        Bundle result=new Bundle();result.putString("stream","Archive production-code tests: "+passed+"/11\n"+failures);
        finish(failures.length()==0?-1:0,result);
    }
}
