package io.muse.x04g;

import android.net.*;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import org.json.*;
import okhttp3.*;
import okio.ByteString;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

final class MuseLink {
    static final byte[] EMPTY=new byte[0];
    static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
    final Store store;
    final Consumer<String> status;
    final Consumer<Reply> reply;
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).build();
    final ConnectivityManager network;
    WebSocket ws;
    long nativeHandle,nextId,controlId,subscribeId,chatId;
    boolean established,connecting,acked,busy,submitted;
    boolean avatarMode,avatarSaved;
    volatile boolean registered,turn;
    long voiceGeneration;
    final java.util.concurrent.atomic.AtomicInteger audioQueued=new java.util.concurrent.atomic.AtomicInteger();
    ScheduledFuture<?> turnTimer;
    volatile boolean closed;
    int failures;
    long lastSeq,lastContent,lastEvent,turnStart;
    String registerId;
    String vmName="";
    final Set<String> userIds=new HashSet<>(),rejected=new HashSet<>();
    // Some subscription messages omit their parent. Remember the preceding
    // turn's IDs so a cancelled reply cannot become the next voice response.
    final Set<String> previousMessages=new HashSet<>();
    final LinkedHashMap<String,StringBuilder> messages=new LinkedHashMap<>();
    final Set<String> done=new HashSet<>();
    final ByteArrayOutputStream control=new ByteArrayOutputStream(),lines=new ByteArrayOutputStream(),ack=new ByteArrayOutputStream();
    ScheduledFuture<?> retry;
    final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {post(()->{if(ws==null)reconnect();});}
        @Override public void onLost(Network n) {post(()->{if(!online())fail("Wi-Fi offline");});}
    };
    MuseLink(Store store,Consumer<String> status,Consumer<Reply> reply) {
        this.store=store;this.status=status;this.reply=reply;
        network=(ConnectivityManager)store.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        network.registerDefaultNetworkCallback(callback);
    }
    void post(Runnable task) {
        if(closed)return;
        try {worker.execute(()->{if(!closed)task.run();});}catch(RejectedExecutionException ignored){}
    }
    static final class Reply {
        final String id,text;final boolean complete;
        Reply(String id,String text,boolean complete){this.id=id;this.text=text;this.complete=complete;}
    }
    boolean online() {
        NetworkCapabilities c=network.getNetworkCapabilities(network.getActiveNetwork());
        return c!=null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    void start() {post(this::connect);}
    void reconnect() {
        post(()->{if(closed)return;if(retry!=null)retry.cancel(false);disconnect();failures=0;connect();});
    }
    static String root(JSONObject p) throws Exception {
        String r=p.optString("api_url_v2","https://api.muse.ai"); if(r.isEmpty())r="https://api.muse.ai";
        HttpUrl u=HttpUrl.parse(r); if(u==null || !u.isHttps() || !u.username().isEmpty() || !u.password().isEmpty())throw new IOException("invalid API root");
        return r.replaceAll("/+$","");
    }
    JSONObject api(String root,String path,String token,JSONObject body) throws Exception {
        Request.Builder b=new Request.Builder().url(root+path).header("Authorization","Bearer "+token).header("X-API-Version","1.0.0").header("User-Agent","muse-x04g/"+Store.VERSION);
        if(body!=null)b.post(RequestBody.create(body.toString(),JSON));
        try(Response r=client.newCall(b.build()).execute()) {
            if(!r.isSuccessful())throw new HttpError(r.code());
            if(r.body()==null)throw new IOException("empty API response");
            if(r.body().contentLength()>1024*1024)throw new IOException("API response too large");
            try(InputStream in=r.body().byteStream()) {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1024*1024)throw new IOException("API response too large");out.write(buf,0,n);}return new JSONObject(out.toString("UTF-8"));}
        }
    }
    JSONObject refresh(JSONObject p) throws Exception {
        String rt=p.getString("refresh_token"); rt=rt.substring(rt.lastIndexOf(':')+1);
        JSONObject t=api(root(p),"/device_token/refresh","hatch_refresh:"+rt,new JSONObject().put("device_id",store.node).put("sdk_token",store.sdkToken()));
        if(t.optJSONObject("payload")!=null)t=t.getJSONObject("payload");
        if(t.optString("access_token").isEmpty() || t.optString("refresh_token").isEmpty())throw new IOException("invalid refresh result");
        p.put("access_token",t.getString("access_token")).put("refresh_token",t.getString("refresh_token")).put("saved_at",System.currentTimeMillis()/1000).put("sdk_reported",true);store.save(p);return p;
    }
    JSONObject fetch(JSONObject p) throws Exception {return api(root(p),"/fetch_vms",p.getString("access_token"),null);}
    void provision(JSONObject c,BiConsumer<JSONObject,Exception> callback) {
        post(()->{
            try {
                JSONObject p=new JSONObject();for(String k:new String[]{"access_token","refresh_token","username","api_url_v2","noise_host"})p.put(k,c.optString(k));p.put("saved_at",System.currentTimeMillis()/1000);
                JSONObject v=fetch(p); if(v.optJSONArray("vm_list")==null)throw new IOException("no VM list");
                callback.accept(p,null);
            } catch(Exception e){callback.accept(null,e);}
        });
    }
    void connect() {
        if(closed || connecting || ws!=null)return;
        try {
            JSONObject p=store.pairing(); if(p==null){status.accept("Unpaired");return;}
            if(!online()){schedule("Wi-Fi offline");return;}
            connecting=true;status.accept("Connecting to Muse");
            if(System.currentTimeMillis()/1000-p.optLong("saved_at")>=3*3600)p=refresh(p);
            else if(!p.optBoolean("sdk_reported")) {
                try {p=refresh(p);}catch(Exception reportFailure){Log.i("MuseX04G","SDK token report refresh failed: "+code(reportFailure));}
            }
            JSONObject v;
            try {v=fetch(p);}catch(HttpError e){if(e.code!=401)throw e;p=refresh(p);v=fetch(p);}
            JSONArray list=v.getJSONArray("vm_list");JSONObject pick=null;
            for(int i=0;i<list.length();i++){JSONObject x=list.optJSONObject(i);if(x!=null&&!x.optString("vm_auth_token").isEmpty()&&(!x.optString("vm_ws_url").isEmpty()||!x.optString("vm_url").isEmpty())&&(pick==null||x.optBoolean("default")))pick=x;}
            if(pick==null)throw new IOException("no VM available");
            vmName=pick.optString("vm_name");String vm=pick.optString("vm_id");
            if(vm.isEmpty()) {HttpUrl u=HttpUrl.parse(pick.optString("vm_ws_url",pick.optString("vm_url")).replace("wss://","https://"));if(u==null)throw new IOException("invalid VM URL");vm=u.host().split("\\.")[0];}
            String host=p.optString("noise_host");if(host.isEmpty())host="hatch.metaaivm.com";
            if(!host.matches("[A-Za-z0-9.-]+"))throw new IOException("invalid Noise host");
            HttpUrl url=new HttpUrl.Builder().scheme("https").host(host).addPathSegments("v1/noise").addQueryParameter("vm_id",vm).build();
            Request req=new Request.Builder().url(url).header("Authorization","Bearer "+pick.getString("vm_auth_token")).header("User-Agent","muse-x04g/"+Store.VERSION).build();
            ws=client.newWebSocket(req,listener);WebSocket attempt=ws;
            worker.schedule(()->{if(connecting&&ws==attempt)fail("Connection timeout");},25,TimeUnit.SECONDS);
        } catch(Exception e) {connecting=false;schedule("Connect failed: "+code(e));}
    }
    final WebSocketListener listener=new WebSocketListener() {
        @Override public void onOpen(WebSocket socket,Response response) {post(()->{
            if(socket!=ws || closed){socket.cancel();return;}
            try {nativeHandle=Native.create();nextId=1;socket.send(ByteString.of(Native.hello(nativeHandle)));}catch(Exception e){fail("Noise start failed");}
        });}
        @Override public void onMessage(WebSocket socket,ByteString bytes) {post(()->{
            if(socket!=ws || closed)return;
            try {
                if(!established){socket.send(ByteString.of(Native.finish(nativeHandle,bytes.toByteArray())));established=true;openStreams();return;}
                Native.Frame f=Native.receive(nativeHandle,bytes.toByteArray());if(f!=null)frame(f);
            }catch(Exception e){fail("Protocol error: "+code(e));}
        });}
        @Override public void onFailure(WebSocket socket,Throwable t,Response response) {post(()->{if(socket==ws)fail("Link lost: "+(response==null?t.getClass().getSimpleName():"HTTP "+response.code()));});}
        @Override public void onClosed(WebSocket socket,int code,String reason) {post(()->{if(socket==ws)fail("Link closed: "+code);});}
        @Override public void onClosing(WebSocket socket,int code,String reason) {socket.close(code,null);}
    };
    void send(byte[][] frames) throws Exception {
        for(byte[] b:frames)if(ws==null || !ws.send(ByteString.of(b)))throw new IOException("WebSocket backpressure");
    }
    long request(String path,byte[] data,boolean end) throws Exception {long id=nextId++;send(Native.request(nativeHandle,id,path,UUID.randomUUID().toString(),data,end));return id;}
    void openStreams() throws Exception {
        controlId=request("/link-control",EMPTY,false);subscribeId=request("/chat/subscribe",Pairing.utf("{}"),true);
        registerId=UUID.randomUUID().toString();
        JSONObject params=new JSONObject().put("node_id",store.node).put("display_name","Xiaomi X04G Muse").put("platform","android").put("version",Store.VERSION).put("device_family","homehub").put("model_id","xiaomi-x04g").put("is_wakeup_supported",false).put("commands_v2",new JSONObject());
        controlSend(new JSONObject().put("type","req").put("id",registerId).put("method","link.register").put("params",params));
    }
    void controlSend(JSONObject obj) throws Exception {byte[] b=Pairing.utf(obj.toString());send(Native.body(nativeHandle,controlId,Pairing.join(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.length).array(),b),false));}
    void frame(Native.Frame f) throws Exception {
        if(f.id!=controlId&&f.id!=subscribeId&&f.id!=chatId)return;
        if(f.kind==4 || f.status>=400)throw new HttpError(f.status);
        if(f.id==controlId){control.write(f.data);decodeControl();if(f.end)throw new IOException("control ended");}
        else if(f.id==subscribeId){for(byte b:f.data){if(b=='\n'){if(lines.size()>0)event(new JSONObject(lines.toString("UTF-8")));lines.reset();}else{if(lines.size()>=256*1024)throw new IOException("subscription line too large");lines.write(b);}}if(f.end)throw new IOException("subscription ended");}
        else if(f.id==chatId && turn){if(ack.size()+f.data.length>64*1024)throw new IOException("ack too large");ack.write(f.data);if(f.end){JSONObject a=new JSONObject(ack.toString("UTF-8"));if(a.optJSONObject("result")!=null)a=a.getJSONObject("result");for(String k:new String[]{"message_id","reply_to_message_id"})if(!a.optString(k).isEmpty())userIds.add(a.getString(k));acked=true;status.accept("Thinking");Log.i("MuseX04G","chat acknowledged");}}
    }
    void decodeControl() throws Exception {
        byte[] b=control.toByteArray();int at=0;
        while(b.length-at>=4){int n=ByteBuffer.wrap(b,at,4).order(ByteOrder.LITTLE_ENDIAN).getInt();if(n<0||n>1024*1024)throw new IOException("control length");if(b.length-at-4<n)break;if(n>0)controlMessage(new JSONObject(new String(b,at+4,n,StandardCharsets.UTF_8)));at+=4+n;}
        control.reset();control.write(b,at,b.length-at);
    }
    void controlMessage(JSONObject c) throws Exception {
        if(registerId.equals(c.optString("id")) && !c.has("method")){
            if(c.has("error")&&!c.isNull("error"))throw new IOException("registration rejected");
            registered=true;connecting=false;failures=0;status.accept("Connected: "+vmName);Log.i("MuseX04G","registered with Muse");
        } else if(Arrays.asList("link.unpaired","node.unpaired").contains(c.optString("event"))){store.unpair();disconnect();status.accept("Unpaired by Muse");}
        else if(c.optString("method").equals("link.invoke"))controlSend(new JSONObject().put("method","link.result").put("id",c.optString("id")).put("ok",false).put("error",new JSONObject().put("code","unsupported_command").put("message","Phase 1 device has no command handlers")));
    }
    void requestAvatar(){post(()->{
        try{sendTyped(asset("avatar_prompt.md")+"\n\nCURRENT MUSE_PIXEL.C\n```c\n"+asset("avatar_base.c")+"\n```",true);}catch(Exception e){status.accept("Avatar request failed: "+code(e));}
    });}
    String asset(String name)throws Exception{try(InputStream in=store.context.getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    void sendTyped(String message,boolean avatar) {post(()->{
        try {
            if(!registered){status.accept("Muse not connected");return;}
            cancelTurn();turn=submitted=true;turnStart=lastEvent=lastContent=SystemClock.elapsedRealtime();
            avatarMode=avatar;avatarSaved=false;
            JSONObject obj=new JSONObject().put("message",message).put("output_modality","text").put("device_id",store.node);
            chatId=request("/chat/stream",Pairing.utf(obj.toString()),true);status.accept("Thinking");
            turnTimer=worker.schedule(this::settle,1,TimeUnit.SECONDS);
        } catch(Exception e){fail("Chat failed: "+code(e));}
    });}
    void beginVoice(long generation) {post(()->{
        try {
            if(!registered){status.accept("Muse not connected");return;}
            cancelTurn();voiceGeneration=generation;turn=true;turnStart=lastEvent=lastContent=SystemClock.elapsedRealtime();
            String head="{\"message\":\"\",\"output_modality\":\"text\",\"device_id\":"+JSONObject.quote(store.node)+",\"items\":[{\"type\":\"file\",\"mime_type\":\"audio/wav\",\"filename\":\"voice_note.wav\",\"data_base64\":\"";
            chatId=request("/chat/stream",Pairing.utf(head),false);
        }catch(Exception e){fail("Voice start failed: "+code(e));}
    });}
    boolean voiceChunk(long generation,byte[] bytes,boolean end) {
        if(closed)return false;
        if(audioQueued.addAndGet(bytes.length)>64*1024){audioQueued.addAndGet(-bytes.length);return false;}
        post(()->{
            try {
                if(generation!=voiceGeneration||!turn||chatId==0)return;
                send(Native.body(nativeHandle,chatId,bytes,end));
                if(end){submitted=true;lastEvent=lastContent=SystemClock.elapsedRealtime();status.accept("Thinking");turnTimer=worker.schedule(this::settle,1,TimeUnit.SECONDS);}
            }catch(Exception e){fail("Voice send failed: "+code(e));}
            finally {audioQueued.addAndGet(-bytes.length);Arrays.fill(bytes,(byte)0);}
        });return true;
    }
    void abortVoice(long generation) {post(()->{if(voiceGeneration==generation)try{cancelTurn();}catch(Exception e){fail("Voice cancel failed");}});}
    void cancelTurn() throws Exception {
        if(turnTimer!=null)turnTimer.cancel(false);
        if(chatId!=0 && established)send(Native.reset(nativeHandle,chatId));
        if(!messages.isEmpty()){previousMessages.clear();previousMessages.addAll(messages.keySet());}
        chatId=0;turn=submitted=acked=busy=avatarMode=avatarSaved=false;userIds.clear();rejected.clear();messages.clear();done.clear();ack.reset();reply.accept(new Reply("","",true));
    }
    void event(JSONObject e) throws Exception {
        if(!e.optString("type").equals("event"))return;
        long seq=e.optLong("seq");if(seq>0&&seq<=lastSeq)return;if(seq>lastSeq)lastSeq=seq;
        if(!turn)return;String type=e.optString("event");JSONObject p=e.optJSONObject("payload");if(p==null)return;
        if(type.equals("agent.status")||type.equals("task.status")){if(!submitted)return;String s=p.optString("activity_code",p.optString("status"));busy=!s.isEmpty()&&!Arrays.asList("online","idle","completed","failed").contains(s);lastEvent=SystemClock.elapsedRealtime();return;}
        if(!Arrays.asList("delta.message_start","delta.text_append","delta.message_done","message.assistant").contains(type))return;
        String id=p.optString("message_id");if(id.isEmpty())id=e.optString("message_id");if(id.isEmpty())id=p.optString("id");if(id.isEmpty()||rejected.contains(id))return;
        if(!submitted){if(previousMessages.size()<16)previousMessages.add(id);return;}
        if(previousMessages.contains(id)){Log.i("MuseX04G","reply ignored: cancelled or preceding message");return;}
        String parent=p.optString("reply_to_message_id");if(parent.isEmpty())parent=p.optString("parent_message_id");
        if(acked&&!parent.isEmpty()&&!userIds.contains(parent)&&!messages.containsKey(parent)){rejected.add(id);Log.i("MuseX04G","reply rejected: belongs to another turn");return;}
        if(rejected.size()>=8&&parent.isEmpty()&&!messages.containsKey(id))return;
        if(!messages.containsKey(id)){if(messages.size()>=8)return;Log.i("MuseX04G","reply start acked="+acked+" parent="+(parent.isEmpty()?"absent":userIds.contains(parent)?"current":messages.containsKey(parent)?"followup":"other"));messages.put(id,new StringBuilder());}
        StringBuilder text=messages.get(id);lastEvent=lastContent=SystemClock.elapsedRealtime();
        if(type.equals("delta.text_append")){String t=p.optString("text");if(text.length()+t.length()>64*1024)throw new IOException("reply too large");text.append(t);}
        if(type.equals("delta.message_done")||type.equals("message.assistant")){if(!p.optBoolean("display_text_ready",true)&&!type.equals("delta.message_done"))return;String full=p.optString("display_text",p.optString("content"));if(full.length()>64*1024)throw new IOException("reply too large");if(!full.isEmpty()&&full.length()!=text.length()){text.setLength(0);text.append(full);}done.add(id);}
        if(avatarMode){
            if(!type.equals("delta.text_append")||text.length()/4096!=(text.length()-p.optString("text").length())/4096)Log.i("MuseX04G","Avatar event="+type+" bytes="+text.length());
            if(done.contains(id)&&!avatarSaved){
                String result=text.toString().trim();int first=result.indexOf("```c"),last=first<0?-1:result.indexOf("```",first+4);
                if(first>=0&&last>first){String code=result.substring(first+4,last).trim()+"\n";Store.write(new File(store.context.getCacheDir(),"avatar-candidate.c"),code);avatarSaved=true;Log.i("MuseX04G","Avatar candidate ready bytes="+code.length());}
                else if(result.startsWith("NO AVATAR")){Store.write(new File(store.context.getCacheDir(),"avatar-result"),"NO AVATAR");avatarSaved=true;Log.i("MuseX04G","Muse has no custom avatar; using official fallback");}
            }
        }else{reply.accept(new Reply(id,text.toString(),done.contains(id)));status.accept("Muse reply");}
    }
    void settle() {
        if(closed||!turn)return;long now=SystemClock.elapsedRealtime();
        if(now-turnStart>(avatarMode?900000:180000) || (messages.isEmpty()&&now-turnStart>(avatarMode?900000:60000))){status.accept("Muse reply timeout");try{cancelTurn();}catch(Exception ignored){}return;}
        if(!messages.isEmpty()&&done.size()==messages.size()&&now-lastEvent>=3000&&(!busy||now-lastContent>=20000)){turn=false;status.accept("Connected: "+vmName);previousMessages.clear();previousMessages.addAll(messages.keySet());messages.clear();done.clear();ack.reset();userIds.clear();rejected.clear();chatId=0;return;}
        turnTimer=worker.schedule(this::settle,1,TimeUnit.SECONDS);
    }
    void disconnect() {
        if(turnTimer!=null)turnTimer.cancel(false);
        WebSocket old=ws;ws=null;if(old!=null)old.cancel();
        if(nativeHandle!=0)Native.destroy(nativeHandle);nativeHandle=0;
        connecting=established=registered=turn=submitted=acked=busy=false;controlId=subscribeId=chatId=lastSeq=0;
        control.reset();lines.reset();ack.reset();messages.clear();done.clear();userIds.clear();rejected.clear();previousMessages.clear();reply.accept(new Reply("","",true));
    }
    void fail(String s) {disconnect();schedule(s);}
    void schedule(String s) {
        if(closed)return;status.accept(s);Log.i("MuseX04G",s);
        if(retry!=null)retry.cancel(false);int seconds=Math.min(60,2<<Math.min(failures++,5));retry=worker.schedule(this::connect,seconds,TimeUnit.SECONDS);
    }
    void close() {
        if(closed)return;
        closed=true;
        client.dispatcher().cancelAll();
        worker.execute(()->{if(retry!=null)retry.cancel(false);disconnect();network.unregisterNetworkCallback(callback);client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();worker.shutdownNow();});
    }
    static String code(Exception e) {return e instanceof HttpError?"HTTP "+((HttpError)e).code:e.getClass().getSimpleName();}
    static final class HttpError extends IOException {final int code;HttpError(int code){super("HTTP "+code);this.code=code;}}
}
