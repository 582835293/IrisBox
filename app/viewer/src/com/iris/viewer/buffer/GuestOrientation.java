package com.rootvmos.viewer;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authenticated Host control only; no root shell or graphics on this channel. */
final class GuestOrientation {
    static final String[] MODES={"auto","landscape","portrait"};
    static boolean validToken(String token){return token!=null&&token.matches("[a-f0-9]{64}");}
    private static boolean validMode(String mode){return "auto".equals(mode)||"landscape".equals(mode)||"portrait".equals(mode);}

    static JSONObject state(String token,int id,String session) throws Exception {
        if(!validToken(token)||id<1007||id>1134||!session.matches("[1-9][0-9]*:[1-9][0-9]*"))throw new IOException("Invalid orientation identity");
        JSONArray rows=request(token,"/state?offset=0&limit=128",false).getJSONArray("instances");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(row.getInt("id")==id){
                if(!session.equals(row.optString("session"))||!row.optBoolean("ready"))throw new IOException("Instance session changed");
                JSONObject orientation=row.getJSONObject("orientation");
                if(!orientation.optBoolean("valid")||!validMode(orientation.optString("mode")))throw new IOException("Invalid orientation state");
                return row;
            }
        }
        throw new IOException("Instance missing");
    }

    static void apply(String token,int id,String session,String mode,String previous) throws Exception {
        if(!validMode(mode)||!validMode(previous))throw new IOException("Invalid orientation mode");
        JSONObject before=state(token,id,session);
        if(!before.optString("busy").isEmpty()||!previous.equals(before.getJSONObject("orientation").getString("mode")))throw new IOException("Orientation changed");
        request(token,"/action?id="+id+"&type=orientation&session="+session+"&profile="+mode+"&previousProfile="+previous,true);
        long end=SystemClock.elapsedRealtime()+35000;
        while(SystemClock.elapsedRealtime()<end){
            JSONObject current=state(token,id,session);
            if(current.optString("busy").isEmpty()){
                if(!current.optString("error").isEmpty()||!mode.equals(current.getJSONObject("orientation").getString("mode")))throw new IOException("Orientation action failed");
                return;
            }
            Thread.sleep(250);
        }
        throw new IOException("Orientation deadline");
    }

    private static JSONObject request(String token,String path,boolean post) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:19762"+path).openConnection();
        try{
            connection.setConnectTimeout(4000);connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod(post?"POST":"GET");
            connection.setRequestProperty("Authorization","Bearer "+token);
            if(post){connection.setDoOutput(true);connection.setFixedLengthStreamingMode(0);connection.getOutputStream().close();}
            int code=connection.getResponseCode();if(code!=(post?202:200))throw new IOException("Orientation HTTP "+code);
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=input.read(buffer))!=-1){if(output.size()+n>262144)throw new IOException("State reply too large");output.write(buffer,0,n);}
                return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{connection.disconnect();}
    }
}
