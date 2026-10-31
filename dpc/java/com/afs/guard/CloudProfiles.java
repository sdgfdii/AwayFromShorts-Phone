package com.afs.guard;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.net.URI;
import org.json.JSONObject;
final class CloudProfiles {
    static JSONObject load(Context c,String provider){try{return new JSONObject(c.getSharedPreferences("afs_cloud_profiles",0).getString(provider,"{}"));}catch(Exception e){throw new IllegalStateException(T.s("云端配置读取失败"),e);}}
    static void validate(String provider,JSONObject p)throws Exception{
        String target=p.optString("target").trim(),branch=p.optString("branch").trim(),path=p.optString("path").trim();
        if(provider.equals("github")){if(!target.matches("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+"))throw new IllegalArgumentException(T.s("请填写 owner/private-repository"));}
        else if(provider.equals("webdav")||provider.equals("git")){
            boolean scp=provider.equals("git")&&target.matches("[A-Za-z0-9_.-]+@[A-Za-z0-9.-]+:[A-Za-z0-9_./-]+")&&!target.contains("..");
            if(!scp){URI u=new URI(target);String scheme=u.getScheme();if(u.getHost()==null||u.getHost().isEmpty()||u.getFragment()!=null||u.getQuery()!=null||!("https".equals(scheme)||provider.equals("git")&&"ssh".equals(scheme)))throw new IllegalArgumentException(T.s("请使用 HTTPS 地址，Git 也支持 SSH 地址"));if(u.getRawUserInfo()!=null&&(!provider.equals("git")||u.getRawUserInfo().contains(":")))throw new IllegalArgumentException(T.s("账号和密码请填写在单独字段中"));}
        }else throw new IllegalArgumentException(T.s("不支持的云端类型"));
        if(!provider.equals("webdav")&&(branch.isEmpty()||branch.startsWith("-")||branch.contains("..")||!branch.matches("[A-Za-z0-9_./-]+")))throw new IllegalArgumentException(T.s("分支名无效"));
        if(path.startsWith("/")||path.contains("\\")||path.contains("..")||path.contains(":"))throw new IllegalArgumentException(T.s("目录须为不含 .. 的相对路径"));
    }
    private static javax.crypto.SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);String alias="afs-cloud-profile-v1";if(!store.containsAlias(alias)){KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}return (javax.crypto.SecretKey)store.getKey(alias,null);}
    static synchronized void save(Context c,String provider,JSONObject profile,String secret)throws Exception{validate(provider,profile);JSONObject old=load(c,provider);if(secret!=null&&!secret.isEmpty()){Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());profile.put("credential",Base64.encodeToString(cipher.doFinal(secret.getBytes("UTF-8")),Base64.NO_WRAP)).put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));}else if(old.has("credential")){profile.put("credential",old.getString("credential")).put("iv",old.getString("iv"));}if(!c.getSharedPreferences("afs_cloud_profiles",0).edit().putString(provider,profile.toString()).commit())throw new IllegalStateException(T.s("保存失败"));}
    static synchronized void remove(Context c,String provider){if(!c.getSharedPreferences("afs_cloud_profiles",0).edit().remove(provider).commit())throw new IllegalStateException(T.s("删除失败"));}
}
