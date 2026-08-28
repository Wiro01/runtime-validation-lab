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
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private WebView web;
    private static byte[] sha(String s) throws Exception { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
    private static String hex(byte[] b) { StringBuilder x=new StringBuilder(); for(byte v:b)x.append(String.format("%02x",v)); return x.toString(); }
    private static byte[] b64u(String s) { return Base64.decode(s,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING); }
    private static String gunzip(byte[] raw) throws Exception { GZIPInputStream gz=new GZIPInputStream(new ByteArrayInputStream(raw)); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n; while((n=gz.read(buf))>0)out.write(buf,0,n); gz.close(); return out.toString("UTF-8"); }
    private static JSONObject decodePacket(String msg,String secret) throws Exception {
        String[] p=msg.split("\\."); if(p.length!=4||!"v1".equals(p[0]))throw new Exception("bad packet");
        byte[] iv=b64u(p[1]),ct=b64u(p[2]),tag=b64u(p[3]); Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(sha("mac|"+secret),"HmacSHA256")); mac.update(iv); byte[] calc=mac.doFinal(ct); if(!MessageDigest.isEqual(tag,calc))throw new Exception("auth failed");
        Cipher aes=Cipher.getInstance("AES/CBC/PKCS5Padding"); aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(sha("enc|"+secret),"AES"),new IvParameterSpec(iv)); JSONObject o=new JSONObject(gunzip(aes.doFinal(ct)));
        if(!"WRO_FLEET_SNAPSHOT".equals(o.optString("message_type"))||o.optJSONArray("rows")==null||o.optJSONArray("rows").length()==0)throw new Exception("not fleet snapshot"); return o;
    }
    private static JSONObject findLatest(String topic,String secret,String since) throws Exception {
        URLConnection c=new URL("https://ntfy.sh/"+topic+"/json?poll=1&since="+since+"&_="+System.currentTimeMillis()).openConnection(); c.setUseCaches(false); c.setConnectTimeout(6000); c.setReadTimeout(6000);
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)); String line; JSONObject best=null; long bestSeq=Long.MIN_VALUE;
        while((line=br.readLine())!=null){ if(line.trim().isEmpty())continue; try{JSONObject m=new JSONObject(line); if(!"message".equals(m.optString("event")))continue; String packet=m.optString("message",""); if(!packet.startsWith("v1."))continue; JSONObject o=decodePacket(packet,secret); long seq=o.optLong("fleet_snapshot_sequence",o.optLong("snapshot_sequence",0)); if(best==null||seq>=bestSeq){best=o;bestSeq=seq;}}catch(Exception ignored){} }
        br.close(); return best;
    }
    public class Bridge {
        @JavascriptInterface public String getLiveState(String secret) {
            String err="";
            try { String topic="wro-"+hex(sha("topic|"+secret)).substring(0,24); JSONObject o=findLatest(topic,secret,"2h"); if(o==null)throw new Exception("no valid snapshot"); o.put("__transport","ntfy"); o.put("__relay_ok",true); o.put("__relay_fetched_at",System.currentTimeMillis()); String raw=o.toString(); getSharedPreferences("wro_monitor",MODE_PRIVATE).edit().putString("last_state",raw).apply(); return raw; }
            catch(Exception e){ err=e.getClass().getSimpleName()+": "+e.getMessage(); try{String raw=getSharedPreferences("wro_monitor",MODE_PRIVATE).getString("last_state",""); if(!raw.isEmpty()){JSONObject o=new JSONObject(raw); o.put("__transport","cache"); o.put("__relay_ok",false); o.put("__relay_error",err); return o.toString();} return new JSONObject().put("__error",err).toString();}catch(Exception x){return "{\"__error\":\"native failure\"}";} }
        }
    }
    @Override public void onCreate(Bundle state) { super.onCreate(state); web=new WebView(this); WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowFileAccessFromFileURLs(true); s.setAllowUniversalAccessFromFileURLs(true); s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(false); web.addJavascriptInterface(new Bridge(),"WRO"); web.setWebViewClient(new WebViewClient()); setContentView(web); web.loadUrl("file:///android_asset/index.html"); }
    @Override public void onBackPressed() { if(web!=null&&web.canGoBack())web.goBack(); else super.onBackPressed(); }
}
