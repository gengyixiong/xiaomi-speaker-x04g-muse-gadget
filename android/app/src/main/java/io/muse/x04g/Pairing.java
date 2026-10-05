package io.muse.x04g;

// Android port of Meta's linux/src/musegadget/pairing.py (Apache-2.0).
import android.os.SystemClock;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;

final class Pairing {
    static final String LABEL="hatch-link ble setup v1";
    private final Store store;
    private byte[] rxKey,txKey;
    private String sid;
    private long rx,tx,deadline;
    private boolean confirmed;
    Pairing(Store store) { this.store=store; }
    static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] un64(String s) throws GeneralSecurityException {
        if(s.length()==0 || s.length()>16384 || s.length()%4==1 || !s.matches("[A-Za-z0-9_-]+")) throw new GeneralSecurityException("invalid base64url");
        return Base64.getUrlDecoder().decode(s);
    }
    static byte[] join(byte[]... arrays) {
        int n=0; for(byte[] a:arrays) n+=a.length;
        byte[] out=new byte[n]; int p=0; for(byte[] a:arrays) { System.arraycopy(a,0,out,p,a.length); p+=a.length; } return out;
    }
    static byte[] sha(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    static byte[] hmac(byte[] key,byte[] data) throws Exception {
        Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key,"HmacSHA256")); return m.doFinal(data);
    }
    static byte[][] keys(byte[] secret,byte[] mn,byte[] dn,byte[] hash) throws Exception {
        byte[] prk=hmac(sha(join(mn,dn,hash)),secret);
        byte[] session=hmac(prk,join(utf(LABEL),new byte[]{1}));
        return new byte[][]{hmac(session,join(utf("mobile->device"),new byte[]{1})),hmac(session,join(utf("device->mobile"),new byte[]{1})),Arrays.copyOf(sha(join(utf("hatch-link session id v1"),hash,secret)),16)};
    }
    static byte[] crypt(boolean encrypt,byte[] key,String sid,int direction,long counter,byte[] data) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        byte[] nonce=ByteBuffer.allocate(12).putInt(direction<<24).putLong(counter).array();
        c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        c.updateAAD(utf(LABEL+"|"+sid+"|"+(direction==0?"m2d":"d2m")+"|"+Long.toUnsignedString(counter)));
        return c.doFinal(data);
    }
    static ECParameterSpec curve() throws Exception {
        AlgorithmParameters a=AlgorithmParameters.getInstance("EC"); a.init(new ECGenParameterSpec("secp256r1")); return a.getParameterSpec(ECParameterSpec.class);
    }
    static PublicKey publicKey(byte[] p) throws Exception {
        if(p.length!=65 || p[0]!=4) throw new GeneralSecurityException("invalid public point");
        ECParameterSpec spec=curve();
        BigInteger x=new BigInteger(1,Arrays.copyOfRange(p,1,33)), y=new BigInteger(1,Arrays.copyOfRange(p,33,65));
        BigInteger prime=((ECFieldFp)spec.getCurve().getField()).getP();
        if(x.compareTo(prime)>=0 || y.compareTo(prime)>=0 || !y.multiply(y).mod(prime).equals(x.multiply(x).multiply(x).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(prime))) throw new GeneralSecurityException("point off curve");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x,y),spec));
    }
    static byte[] point(ECPublicKey p) {
        byte[] out=new byte[65]; out[0]=4;
        BigInteger[] xy={p.getW().getAffineX(),p.getW().getAffineY()};
        for(int i=0;i<2;i++) { byte[] b=xy[i].toByteArray(); int n=Math.min(32,b.length); System.arraycopy(b,b.length-n,out,1+i*32+32-n,n); }
        return out;
    }
    static String transcript(JSONObject p) throws Exception {
        StringBuilder s=new StringBuilder("hatch-link-pairing-v5\nversion=5\ninitiator_role=mobile\nresponder_role=link");
        for(String k:new String[]{"device_id","node_id","mac","model","firmware_version","selected_cipher_suite","pairing_auth","pairing_auth_epoch","pairing_policy","confirm_timeout_seconds","mobile_pub","device_pub","mobile_nonce","device_nonce"}) s.append('\n').append(k).append('=').append(p.get(k));
        return s.toString();
    }
    void clear() {
        if(rxKey!=null) Arrays.fill(rxKey,(byte)0); if(txKey!=null) Arrays.fill(txKey,(byte)0);
        rxKey=txKey=null; sid=null; rx=tx=deadline=0; confirmed=false;
    }
    JSONObject info() throws Exception {
        return new JSONObject().put("device_id",store.device).put("node_id",store.node).put("mac",store.mac).put("model","hatch_link").put("pairing_protocol",5).put("pairing_auth","none").put("pairing_auth_epoch",0).put("pairing_policy","confirm_app");
    }
    JSONObject hello(JSONObject h) throws Exception {
        clear();
        if(!(h.opt("version") instanceof Number) || h.getDouble("version")!=5 || !"none".equals(h.optString("pairing_auth")) || !"confirm_app".equals(h.optString("pairing_policy"))) throw new GeneralSecurityException("error_pairing_invalid_hello");
        byte[] mobile=un64(h.getString("mobile_pub")),mn=un64(h.getString("mobile_nonce"));
        if(mn.length!=16) throw new GeneralSecurityException("error_pairing_invalid_hello");
        KeyPairGenerator gen=KeyPairGenerator.getInstance("EC"); gen.initialize(new ECGenParameterSpec("secp256r1")); KeyPair kp=gen.generateKeyPair();
        KeyAgreement dh=KeyAgreement.getInstance("ECDH"); dh.init(kp.getPrivate()); dh.doPhase(publicKey(mobile),true); byte[] secret=dh.generateSecret();
        byte[] dn=new byte[16]; new SecureRandom().nextBytes(dn);
        JSONObject ready=info().put("type","pairing_ready").put("version",5).put("firmware_version",Store.VERSION).put("selected_cipher_suite","p256-hkdf-sha256-aes-gcm-v1").put("confirm_timeout_seconds",0).put("mobile_pub",b64(mobile)).put("mobile_nonce",b64(mn)).put("device_pub",b64(point((ECPublicKey)kp.getPublic()))).put("device_nonce",b64(dn));
        byte[] hash=sha(utf(transcript(ready))); byte[][] k=keys(secret,mn,dn,hash); Arrays.fill(secret,(byte)0);
        rxKey=k[0]; txKey=k[1]; sid=b64(k[2]); deadline=SystemClock.elapsedRealtime()+60000;
        ready.put("transcript_hash",b64(hash)).put("session_id",sid);
        ready.remove("mobile_pub"); ready.remove("mobile_nonce"); return ready;
    }
    private void valid() throws Exception {
        if(sid==null || SystemClock.elapsedRealtime()>deadline) { clear(); throw new GeneralSecurityException("error_pairing_decrypt"); }
    }
    JSONObject decrypt(JSONObject env) throws Exception {
        try {
            valid(); if(rx==-1L)throw new GeneralSecurityException("counter exhausted");String counter=env.getString("counter");
            if(!counter.matches("[0-9]+") || Long.parseUnsignedLong(counter)!=rx || !sid.equals(env.getString("session_id"))) throw new GeneralSecurityException("error_pairing_decrypt");
            byte[] tag=un64(env.getString("tag")); if(tag.length!=16) throw new GeneralSecurityException("error_pairing_decrypt");
            JSONObject p=new JSONObject(new String(crypt(false,rxKey,sid,0,rx,join(un64(env.getString("ciphertext")),tag)),StandardCharsets.UTF_8)); rx++; return p;
        } catch(Exception e) { clear(); throw e; }
    }
    void confirm(JSONObject command) throws Exception {
        valid(); if(rx!=1 || command.length()!=1 || !"pairing_client_finished".equals(command.optString("action"))) { clear(); throw new GeneralSecurityException("error_pairing_decrypt"); }
        confirmed=true; deadline=SystemClock.elapsedRealtime()+120000;
    }
    boolean confirmed() { return confirmed && sid!=null && SystemClock.elapsedRealtime()<deadline; }
    JSONObject encrypt(JSONObject p) throws Exception {
        valid(); if(tx==-1L)throw new GeneralSecurityException("counter exhausted");byte[] b=crypt(true,txKey,sid,1,tx,utf(p.toString()));
        return new JSONObject().put("type","pairing_encrypted").put("session_id",sid).put("counter",Long.toUnsignedString(tx++)).put("ciphertext",b64(Arrays.copyOf(b,b.length-16))).put("tag",b64(Arrays.copyOfRange(b,b.length-16,b.length)));
    }
    static void selfCheck(JSONObject v) throws Exception {
        byte[] hash=sha(utf(transcript(v)));
        if(!b64(hash).equals(v.getString("transcript_hash"))) throw new AssertionError("transcript fixture");
        PrivateKey pk=KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(v.getString("device_private_scalar_hex"),16),curve()));
        KeyAgreement dh=KeyAgreement.getInstance("ECDH"); dh.init(pk); dh.doPhase(publicKey(un64(v.getString("mobile_pub"))),true);
        byte[][] k=keys(dh.generateSecret(),un64(v.getString("mobile_nonce")),un64(v.getString("device_nonce")),hash);
        if(!b64(k[2]).equals(v.getString("session_id"))) throw new AssertionError("session fixture");
        byte[] plain=crypt(false,k[0],b64(k[2]),0,0,join(un64(v.getString("client_finished_ciphertext")),un64(v.getString("client_finished_tag"))));
        if(!new String(plain,StandardCharsets.UTF_8).equals(v.getString("client_finished_plaintext"))) throw new AssertionError("crypto fixture");
    }
}
