package com.wro.monitor;

import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private WebView web;
    private final ScheduledExecutorService relayWorker=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean fetching=new AtomicBoolean(false);
    private final AtomicBoolean fetchQueued=new AtomicBoolean(false);
    private volatile String activeSecret="";
    private volatile long lastFetchStart=0L;

    private static byte[] sha(String s) throws Exception { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
    private static String hex(byte[] b) { StringBuilder x=new StringBuilder(); for(byte v:b)x.append(String.format("%02x",v)); return x.toString(); }
    private static byte[] b64u(String s) { return Base64.decode(s,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING); }
    private static String gunzip(byte[] raw) throws Exception { GZIPInputStream gz=new GZIPInputStream(new ByteArrayInputStream(raw)); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n; while((n=gz.read(buf))>0)out.write(buf,0,n); gz.close(); return out.toString("UTF-8"); }
    private static long seq(JSONObject o){ return o==null?0L:o.optLong("fleet_snapshot_sequence",o.optLong("snapshot_sequence",0L)); }
    private static boolean newer(JSONObject incoming,JSONObject current){ if(current==null)return true; long a=seq(incoming),b=seq(current); if(a!=0L||b!=0L)return a>b; return incoming.optString("generated_at",incoming.optString("timestamp","")).compareTo(current.optString("generated_at",current.optString("timestamp","")))>0; }

    private static JSONObject decodePacket(String msg,String secret) throws Exception {
        String[] p=msg.split("\\."); if(p.length!=4||!"v1".equals(p[0]))throw new Exception("bad packet");
        byte[] iv=b64u(p[1]),ct=b64u(p[2]),tag=b64u(p[3]); Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(sha("mac|"+secret),"HmacSHA256")); mac.update(iv); byte[] calc=mac.doFinal(ct); if(!MessageDigest.isEqual(tag,calc))throw new Exception("auth failed");
        Cipher aes=Cipher.getInstance("AES/CBC/PKCS5Padding"); aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(sha("enc|"+secret),"AES"),new IvParameterSpec(iv)); JSONObject o=new JSONObject(gunzip(aes.doFinal(ct)));
        if(!"WRO_FLEET_SNAPSHOT".equals(o.optString("message_type"))||o.optJSONArray("rows")==null||o.optJSONArray("rows").length()==0)throw new Exception("not fleet snapshot"); return o;
    }

    private static JSONObject findLatest(String topic,String secret,String since) throws Exception {
        URLConnection c=new URL("https://ntfy.sh/"+topic+"/json?poll=1&since="+since+"&_="+System.currentTimeMillis()).openConnection(); c.setUseCaches(false); c.setConnectTimeout(5000); c.setReadTimeout(5000);
        c.setRequestProperty("Cache-Control","no-cache, no-store"); c.setRequestProperty("Pragma","no-cache");
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)); String line; JSONObject best=null; long bestSeq=Long.MIN_VALUE;
        while((line=br.readLine())!=null){ if(line.trim().isEmpty())continue; try{JSONObject m=new JSONObject(line); if(!"message".equals(m.optString("event")))continue; String packet=m.optString("message",""); if(!packet.startsWith("v1."))continue; JSONObject o=decodePacket(packet,secret); long s=seq(o); if(best==null||s>bestSeq){best=o;bestSeq=s;}}catch(Exception ignored){} }
        br.close(); return best;
    }

    private void fetchAndStore(String secret){
        if(secret==null||secret.length()<20||!fetching.compareAndSet(false,true))return;
        lastFetchStart=System.currentTimeMillis();
        try{
            String topic="wro-"+hex(sha("topic|"+secret)).substring(0,24);
            JSONObject o=findLatest(topic,secret,"30m"); if(o==null)o=findLatest(topic,secret,"2h"); if(o==null)throw new Exception("no valid snapshot");
            long now=System.currentTimeMillis(); o.put("__transport","ntfy"); o.put("__relay_ok",true); o.put("__relay_fetched_at",now);
            android.content.SharedPreferences prefs=getSharedPreferences("wro_monitor",MODE_PRIVATE); JSONObject current=null; String raw=prefs.getString("last_state",""); if(!raw.isEmpty()){try{current=new JSONObject(raw);}catch(Exception ignored){}}
            android.content.SharedPreferences.Editor edit=prefs.edit().putLong("last_fetch_ok",now).remove("last_error"); if(newer(o,current))edit.putString("last_state",o.toString()); edit.apply();
        }catch(Exception e){getSharedPreferences("wro_monitor",MODE_PRIVATE).edit().putString("last_error",e.getClass().getSimpleName()+": "+e.getMessage()).apply();}
        finally{fetching.set(false);}
    }

    private void queueFetch(String secret){
        if(secret==null||secret.length()<20||!fetchQueued.compareAndSet(false,true))return;
        final String f=secret;
        try{relayWorker.execute(()->{try{fetchAndStore(f);}finally{fetchQueued.set(false);}});}catch(RejectedExecutionException e){fetchQueued.set(false);}
    }

    private void triggerFetch(String secret){
        if(secret!=null&&secret.length()>=20){activeSecret=secret;getSharedPreferences("wro_monitor",MODE_PRIVATE).edit().putString("pair_secret",secret).apply();}
        String s=activeSecret;if(s.length()<20)s=getSharedPreferences("wro_monitor",MODE_PRIVATE).getString("pair_secret","");
        if(s.length()>=20 && System.currentTimeMillis()-lastFetchStart>8000)queueFetch(s);
    }

    public class Bridge {
        @JavascriptInterface public String getLiveState(String secret) {
            triggerFetch(secret);
            try{
                String raw=getSharedPreferences("wro_monitor",MODE_PRIVATE).getString("last_state","");
                if(raw.isEmpty())return new JSONObject().put("__error","warming up relay cache").toString();
                JSONObject o=new JSONObject(raw); long ok=getSharedPreferences("wro_monitor",MODE_PRIVATE).getLong("last_fetch_ok",0L); String err=getSharedPreferences("wro_monitor",MODE_PRIVATE).getString("last_error","");
                o.put("__relay_ok",ok>0 && System.currentTimeMillis()-ok<120000L); o.put("__relay_fetched_at",ok); if(!err.isEmpty())o.put("__relay_error",err); return o.toString();
            }catch(Exception e){return "{\"__error\":\"cache read failure\"}";}
        }
        @JavascriptInterface public void refreshNow(String secret){triggerFetch(secret);}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); activeSecret=getSharedPreferences("wro_monitor",MODE_PRIVATE).getString("pair_secret","");
        relayWorker.scheduleWithFixedDelay(()->fetchAndStore(activeSecret),0,15,TimeUnit.SECONDS);
        web=new WebView(this); WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowFileAccessFromFileURLs(true); s.setAllowUniversalAccessFromFileURLs(true); s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(false); web.addJavascriptInterface(new Bridge(),"WRO"); web.setWebViewClient(new WebViewClient()); setContentView(web); web.loadUrl("file:///android_asset/index.html");
    }
    @Override protected void onResume(){super.onResume();triggerFetch(activeSecret);if(web!=null)web.evaluateJavascript("window.WRO_FORCE_REFRESH&&window.WRO_FORCE_REFRESH()",null);}
    @Override protected void onDestroy(){relayWorker.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed() { if(web!=null&&web.canGoBack())web.goBack(); else super.onBackPressed(); }
}
