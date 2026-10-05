package io.muse.x04g;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

final class Store {
    static final String VERSION="0.2.0";
    final Context context;
    final SharedPreferences prefs;
    final String mac, node, device, name;
    Store(Context context) {
        this.context=context;
        prefs=context.getSharedPreferences("muse",Context.MODE_PRIVATE);
        String m=prefs.getString("mac",null);
        if(m==null) {
            byte[] b=new byte[6]; new SecureRandom().nextBytes(b); b[0]=(byte)((b[0]&0xfc)|2);
            m=String.format(java.util.Locale.ROOT,"%02x:%02x:%02x:%02x:%02x:%02x",b[0],b[1],b[2],b[3],b[4],b[5]);
            if(!prefs.edit().putString("mac",m).commit()) throw new IllegalStateException("identity save failed");
        }
        mac=m; String suffix=m.replace(":","").substring(6);
        node="homelink-"+suffix; device="hatch-link:"+m; name="MuseGadget"+suffix.toUpperCase(java.util.Locale.ROOT);
    }
    String sdkToken() throws Exception {
        String token=new String(Files.readAllBytes(new File(context.getFilesDir(),"sdk-token").toPath()),StandardCharsets.UTF_8).trim();
        if(!token.matches("mgst_[A-Za-z0-9_-]+")) throw new IllegalStateException("SDK token missing or invalid");
        return token;
    }
    JSONObject pairing() throws Exception {
        String p=prefs.getString("pairing",null); return p==null?null:new JSONObject(p);
    }
    void save(JSONObject p) {
        if(!prefs.edit().putString("pairing",p.toString()).commit()) throw new IllegalStateException("credentials save failed");
    }
    void unpair() {
        if(!prefs.edit().remove("pairing").commit()) throw new IllegalStateException("credentials removal failed");
    }
    static void write(File path,String value)throws Exception {
        File tmp=new File(path.getPath()+".tmp");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){out.write(Pairing.utf(value));}
        if(!tmp.renameTo(path))throw new java.io.IOException("private file rename failed");
    }
}
