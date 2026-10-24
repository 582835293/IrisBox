package com.rootvmos.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;

/** Ordinary Guest app: WebView -> Android DownloadManager -> system installer.
 * No root, Host controller, JavaScript bridge, TLS bypass or silent install.
 */
public final class BrowserActivity extends Activity {
    private static final String VIA="https://viayoo.com/zh-cn/";
    private static final String APPBAO="https://sj.qq.com/appdetail/com.tencent.android.qqdownloader";
    private static final String APK="application/vnd.android.package-archive";
    private WebView web;
    private EditText address;
    private DownloadManager manager;
    private final Handler handler=new Handler();
    private LinearLayout downloadRows;
    private AlertDialog downloadDialog;
    private long pendingInstall=-1;
    private boolean resumed;

    private Button button(int label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);
        b.setOnClickListener(v->action.run());return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nav=new LinearLayout(this);
        nav.addView(button(R.string.back,()->{if(web.canGoBack())web.goBack();}));
        address=new EditText(this);address.setSingleLine();address.setHint(R.string.address);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_GO){navigate(address.getText().toString());return true;}return false;});
        nav.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(button(R.string.go,()->navigate(address.getText().toString())));root.addView(nav);
        LinearLayout links=new LinearLayout(this);
        links.addView(button(R.string.via,()->navigate(VIA)));
        links.addView(button(R.string.appbao,()->navigate(APPBAO)));
        links.addView(button(R.string.downloads,this::showDownloads));root.addView(links);
        web=new WebView(this);web.setContentDescription("guest-download-webview");
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){address.setText(url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                String scheme=request.getUrl().getScheme();
                if("https".equalsIgnoreCase(scheme))return false;
                if("http".equalsIgnoreCase(scheme)){navigate(request.getUrl().buildUpon().scheme("https").build().toString());return true;}
                if("market".equalsIgnoreCase(scheme))try{startActivity(new Intent(Intent.ACTION_VIEW,request.getUrl()));}catch(Exception e){error(e);}
                return true;
            }
        });
        web.setDownloadListener(this::confirmDownload);
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        String initial=getIntent().getDataString();navigate(initial==null?VIA:initial);
    }
    private void navigate(String value) {
        try {
            Uri uri=Uri.parse(value.trim());
            if("http".equalsIgnoreCase(uri.getScheme()))uri=uri.buildUpon().scheme("https").build();
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException(getString(R.string.https_required));
            address.setText(uri.toString());web.loadUrl(uri.toString());
        }catch(Exception e){error(e);}
    }
    private void confirmDownload(String url,String userAgent,String disposition,String mime,long length) {
        try {
            Uri uri=Uri.parse(url);
            if("http".equalsIgnoreCase(uri.getScheme()))uri=uri.buildUpon().scheme("https").build();
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException(getString(R.string.https_required));
            String name=URLUtil.guessFileName(uri.toString(),disposition,mime).replaceAll("[^A-Za-z0-9_.-]","_");
            // Official APKs can be served as application/octet-stream. URLUtil
            // changes their extension to .bin; retain an explicit APK URL name.
            String pathName=uri.getLastPathSegment();
            if(pathName!=null&&pathName.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")&&!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
                name=pathName.replaceAll("[^A-Za-z0-9_.-]","_");
            if(!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")||name.length()>160)throw new IllegalArgumentException(getString(R.string.apk_only));
            final Uri target=uri;final String filename=name;
            new AlertDialog.Builder(this).setTitle(R.string.download_confirm).setMessage(getString(R.string.download_message,filename))
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.download,(d,w)->{
                    try {
                        DownloadManager.Request request=new DownloadManager.Request(target).setTitle(filename).setMimeType(APK)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,filename);
                        String cookie=CookieManager.getInstance().getCookie(target.toString());
                        if(cookie!=null&&!cookie.isEmpty())request.addRequestHeader("Cookie",cookie);
                        if(userAgent!=null&&!userAgent.isEmpty())request.addRequestHeader("User-Agent",userAgent);
                        long id=manager.enqueue(request);
                        Set<String> ids=new HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("downloads",new HashSet<>()));
                        ids.add(Long.toString(id));getPreferences(MODE_PRIVATE).edit().putStringSet("downloads",ids).apply();
                        Toast.makeText(this,R.string.download_started,Toast.LENGTH_LONG).show();showDownloads();
                    }catch(Exception e){error(e);}
                }).show();
        }catch(Exception e){error(e);}
    }
    private void showDownloads() {
        if(downloadDialog!=null&&downloadDialog.isShowing()){refreshDownloads();return;}
        downloadRows=new LinearLayout(this);downloadRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.addView(downloadRows);
        downloadDialog=new AlertDialog.Builder(this).setTitle(R.string.downloads).setView(scroll).setNegativeButton(R.string.close,null).create();
        downloadDialog.show();refreshDownloads();
    }
    private final Runnable refresh=()->{if(resumed&&downloadDialog!=null&&downloadDialog.isShowing()){refreshDownloads();}};
    private void refreshDownloads() {
        handler.removeCallbacks(refresh);downloadRows.removeAllViews();
        Set<String> ids=getPreferences(MODE_PRIVATE).getStringSet("downloads",new HashSet<>());
        if(ids.isEmpty()) {android.widget.TextView empty=new android.widget.TextView(this);empty.setText(R.string.empty);downloadRows.addView(empty);}
        for(String saved:ids) {
            try {
                final long id=Long.parseLong(saved);
                try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if(c==null||!c.moveToFirst())continue;
                    String title=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    int reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    long received=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    String detail=status==DownloadManager.STATUS_SUCCESSFUL?getString(R.string.complete):
                        status==DownloadManager.STATUS_FAILED?getString(R.string.failed,reason):
                        status==DownloadManager.STATUS_PAUSED?getString(R.string.paused,reason):
                        status==DownloadManager.STATUS_RUNNING?getString(R.string.running,total>0?(int)(100*received/total):0):getString(R.string.waiting);
                    Button row=new Button(this);row.setAllCaps(false);row.setText(title+"\n"+detail);
                    row.setEnabled(status==DownloadManager.STATUS_SUCCESSFUL);row.setOnClickListener(v->openDownload(id));downloadRows.addView(row);
                }
            }catch(Exception e){error(e);break;}
        }
        if(resumed)handler.postDelayed(refresh,1500);
    }
    private void openDownload(long id) {
        try {
            if(!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this).setMessage(R.string.source_permission).setNegativeButton(R.string.cancel,null)
                    .setPositiveButton(R.string.continue_button,(d,w)->{pendingInstall=id;startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}).show();return;
            }
            Uri uri=manager.getUriForDownloadedFile(id);if(uri==null)throw new IllegalStateException(getString(R.string.failed,0));
            Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,APK).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("APK",uri));startActivity(install);
        }catch(Exception e){error(e);}
    }
    private void error(Exception e){new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(e.getMessage()).setPositiveButton(android.R.string.ok,null).show();}
    @Override protected void onResume(){super.onResume();resumed=true;if(downloadDialog!=null&&downloadDialog.isShowing())refreshDownloads();if(pendingInstall>=0&&getPackageManager().canRequestPackageInstalls()){long id=pendingInstall;pendingInstall=-1;handler.post(()->openDownload(id));}}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(downloadDialog!=null)downloadDialog.dismiss();web.destroy();super.onDestroy();}
}
