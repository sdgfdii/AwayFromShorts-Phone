package com.afs.guard;

import android.content.Context;
import android.util.Base64;
import java.net.*;
import java.io.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** User-configured compatible API. No calls are made without an explicit writing action. */
public final class MemoAi {
    private MemoAi(){}
    private static final String PREF="afs_memo_ai",ALIAS="afs_memo_ai_key";
    public static String endpoint(Context c){return c.getSharedPreferences(PREF,0).getString("endpoint","");}
    public static String model(Context c){return c.getSharedPreferences(PREF,0).getString("model","");}
    public static String validateEndpoint(String value){try{URI uri=new URI(value.trim());String host=uri.getHost();if(host==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null||!"https".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException();return uri.toString().replaceAll("/+$","");}catch(Exception e){throw new IllegalArgumentException(T.s("请填写不含账号、查询参数的 HTTPS API 地址"));}}
    private static javax.crypto.SecretKey key()throws Exception{
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias(ALIAS)){
            javax.crypto.KeyGenerator gen=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
        return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
    }
    public static void save(Context c,String endpoint,String model,String secret)throws Exception{
        endpoint=validateEndpoint(endpoint);model=model.trim();if(model.isEmpty()||model.length()>150||model.matches("(?s).*\\p{Cntrl}.*"))throw new IllegalArgumentException(T.s("请填写有效模型名称"));
        if(!endpoint(c).isEmpty()&&!endpoint(c).equals(endpoint)&&secret.isEmpty())throw new IllegalArgumentException(T.s("更改 API 地址时请重新填写密钥"));
        android.content.SharedPreferences.Editor edit=c.getSharedPreferences(PREF,0).edit().putString("endpoint",endpoint).putString("model",model);
        if(!secret.isEmpty()){if(secret.length()>4096||secret.matches("(?s).*\\p{Cntrl}.*"))throw new IllegalArgumentException(T.s("密钥格式无效"));Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());edit.putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("key",Base64.encodeToString(cipher.doFinal(secret.getBytes("UTF-8")),Base64.NO_WRAP));}
        if(!edit.commit())throw new IOException(T.s("设置未保存"));
    }
    public static void clear(Context c){c.getSharedPreferences(PREF,0).edit().clear().commit();}
    private static String secret(Context c)throws Exception{android.content.SharedPreferences p=c.getSharedPreferences(PREF,0);String value=p.getString("key","");if(value.isEmpty())return "";Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)),"UTF-8");}
    public static String request(Context c,String action,String source)throws Exception{
        String url=validateEndpoint(endpoint(c)),model=model(c),token=secret(c);if(model.isEmpty()||token.isEmpty())throw new IOException(T.s("请先设置模型和 API 密钥"));
        if(!url.endsWith("/chat/completions"))url+="/chat/completions";
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();try{
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(45000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Authorization","Bearer "+token);
            JSONObject payload=new JSONObject().put("model",model).put("stream",false).put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","按用户请求处理笔记，只返回处理后的正文，不添加解释。保留原有事实和清单。"))
                    .put(new JSONObject().put("role","user").put("content",action+"：\n"+source)));
            try(OutputStream out=connection.getOutputStream()){out.write(payload.toString().getBytes("UTF-8"));}
            int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException(T.s("AI 服务返回 HTTP ")+code+T.s("，请检查地址、模型和密钥"));
            String content;try(InputStream input=connection.getInputStream()){content=new JSONObject(MemoFiles.read(input,2*1024*1024)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");}
            if(content.trim().isEmpty()||content.length()>MemoStore.MAX_BODY)throw new IOException(T.s("AI 返回内容为空或超过正文上限"));return content;
        }finally{connection.disconnect();}
    }
}
