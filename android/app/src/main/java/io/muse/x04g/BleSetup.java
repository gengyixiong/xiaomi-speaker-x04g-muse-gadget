package io.muse.x04g;

// Android GATT transport for Meta's Community Pairing v5 setup commands.
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class BleSetup {
    static final UUID SERVICE=UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c");
    static final UUID RX=UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"),TX=UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c");
    static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    final Store store;
    final MuseLink link;
    final Pairing pairing;
    final HandlerThread thread=new HandlerThread("MusePairing");
    final Handler worker;
    final BluetoothAdapter adapter;
    final java.util.function.Consumer<String> status;
    BluetoothGattServer server;
    BluetoothLeAdvertiser advertiser;
    BluetoothGattCharacteristic tx;
    BluetoothDevice peer;
    boolean blocked,busy,subscribed,sending,closed;
    int mtu=23,next=0,total=0,generation;
    final ByteArrayOutputStream incoming=new ByteArrayOutputStream();
    final ArrayDeque<byte[]> outgoing=new ArrayDeque<>();
    BleSetup(Store store,MuseLink link,java.util.function.Consumer<String> status) {
        this.store=store; this.link=link; this.status=status; pairing=new Pairing(store);
        adapter=((BluetoothManager)store.context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)).getAdapter();
        thread.start(); worker=new Handler(thread.getLooper());
    }
    void start() { worker.post(()->{
        try {
            if(adapter==null || !adapter.isEnabled() || adapter.getBluetoothLeAdvertiser()==null) throw new IllegalStateException("BLE peripheral unavailable");
            if(!store.prefs.contains("bluetooth_name")) {
                if(!store.prefs.edit().putString("bluetooth_name",adapter.getName()).commit()) throw new IllegalStateException("Bluetooth name backup failed");
            }
            if(!adapter.setName(store.name)) throw new IllegalStateException("Bluetooth name change failed");
            server=((BluetoothManager)store.context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)).openGattServer(store.context,callback);
            if(server==null) throw new IllegalStateException("GATT unavailable");
            BluetoothGattService s=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            s.addCharacteristic(new BluetoothGattCharacteristic(RX,BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,BluetoothGattCharacteristic.PERMISSION_WRITE));
            tx=new BluetoothGattCharacteristic(TX,BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ);
            tx.addDescriptor(new BluetoothGattDescriptor(CCC,BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE)); s.addCharacteristic(tx);
            if(!server.addService(s)) throw new IllegalStateException("GATT service failed");
            worker.postDelayed(this::close,10*60*1000);
        } catch(Exception e) { status.accept("Pairing unavailable: "+e.getClass().getSimpleName()); close(); }
    }); }
    final AdvertiseCallback advertiseCallback=new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) { status.accept("Pairing: "+store.name); }
        @Override public void onStartFailure(int code) { worker.post(()->{status.accept("BLE advertise error "+code);close();}); }
    };
    final BluetoothGattServerCallback callback=new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int result,BluetoothGattService s) { worker.postDelayed(()->{
            if(closed) return;
            if(result!=BluetoothGatt.GATT_SUCCESS) { status.accept("GATT add error "+result); close(); return; }
            advertiser=adapter.getBluetoothLeAdvertiser();
            if(advertiser==null) { close(); return; }
            advertiser.startAdvertising(new AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build(),new AdvertiseData.Builder().setIncludeDeviceName(true).build(),advertiseCallback);
        },600); }
        @Override public void onConnectionStateChange(BluetoothDevice d,int result,int state) { worker.post(()->{
            if(closed) return;
            Log.i("MuseX04G","BLE connection state="+state+" result="+result);
            if(state==BluetoothProfile.STATE_CONNECTED) {
                if(peer!=null && !peer.equals(d)) { server.cancelConnection(d); return; }
                peer=d; status.accept("Pairing: phone connected");
            } else if(d.equals(peer)) { peer=null; generation++;pairing.clear();incoming.reset();outgoing.clear();blocked=busy=subscribed=sending=false;mtu=23;status.accept("Pairing: phone disconnected"); }
        }); }
        @Override public void onMtuChanged(BluetoothDevice d,int m) { worker.post(()->{if(d.equals(peer)){mtu=m;Log.i("MuseX04G","BLE MTU="+m);}}); }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice d,int id,BluetoothGattCharacteristic c,boolean prepared,boolean response,int offset,byte[] value) { worker.post(()->{
            if(closed)return;
            boolean ok=d.equals(peer) && c.getUuid().equals(RX) && !prepared && offset==0 && value.length<=512;
            if(!ok)Log.i("MuseX04G","BLE write rejected: prepared="+prepared+" offset="+offset+" bytes="+value.length);
            if(response)server.sendResponse(d,id,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,offset,null);
            if(ok)packet(value);
        }); }
        @Override public void onCharacteristicReadRequest(BluetoothDevice d,int id,int offset,BluetoothGattCharacteristic c) { worker.post(()->{
            if(closed)return;
            Log.i("MuseX04G","BLE characteristic read offset="+offset);
            byte[] value=tx.getValue();if(value==null)value=new byte[0];
            boolean ok=d.equals(peer)&&TX.equals(c.getUuid())&&offset>=0&&offset<=value.length;
            server.sendResponse(d,id,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_INVALID_OFFSET,offset,ok?Arrays.copyOfRange(value,offset,value.length):null);
        }); }
        @Override public void onDescriptorReadRequest(BluetoothDevice d,int id,int offset,BluetoothGattDescriptor desc) { worker.post(()->{
            if(closed)return;
            Log.i("MuseX04G","BLE descriptor read offset="+offset);
            byte[] value=subscribed?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            boolean ok=d.equals(peer)&&CCC.equals(desc.getUuid())&&offset>=0&&offset<=value.length;
            server.sendResponse(d,id,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_INVALID_OFFSET,offset,ok?Arrays.copyOfRange(value,offset,value.length):null);
        }); }
        @Override public void onDescriptorWriteRequest(BluetoothDevice d,int id,BluetoothGattDescriptor desc,boolean prepared,boolean response,int offset,byte[] value) { worker.post(()->{
            if(closed)return;
            boolean enable=Arrays.equals(value,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok=d.equals(peer)&&CCC.equals(desc.getUuid())&&!prepared&&offset==0&&(enable||Arrays.equals(value,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
            if(response)server.sendResponse(d,id,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null);
            if(ok)subscribed=enable; pump();
            Log.i("MuseX04G","BLE notification subscription accepted="+ok+" enabled="+subscribed);
        }); }
        @Override public void onNotificationSent(BluetoothDevice d,int result) { worker.postDelayed(()->{
            if(closed)return; sending=false;
            if(result!=BluetoothGatt.GATT_SUCCESS) { outgoing.clear();status.accept("BLE notify error "+result); }
            pump();
        },50); }
    };
    void packet(byte[] b) {
        try {
            byte[] raw=b;
            if(b.length>=3 && (b[0]&255)==254) {
                int index=b[1]&255,count=b[2]&255;
                if(index==0 || count!=total) { incoming.reset();next=0;total=count; }
                if(count==0 || index!=next || index>=total || incoming.size()+b.length-3>8192) {incoming.reset();total=next=0;return;}
                incoming.write(b,3,b.length-3);next++;
                if(next!=total)return;
                raw=incoming.toByteArray();incoming.reset();total=next=0;
            }
            if(raw.length>8192)throw new IllegalArgumentException("setup too large");
            handle(new JSONObject(new String(raw,StandardCharsets.UTF_8)),false);
        } catch(Exception e) { Log.i("MuseX04G","Pairing error: "+e.getClass().getSimpleName());pairing.clear();blocked=true;status.accept("Pairing error: "+e.getClass().getSimpleName());sendPlainError("error_pairing_decrypt");if(peer!=null)server.cancelConnection(peer); }
    }
    void handle(JSONObject c,boolean encrypted) throws Exception {
        String a=c.optString("action");
        if(Arrays.asList("get_device_info","pairing_client_hello","pairing_encrypted","pairing_client_finished","wifi_scan","provision_v2").contains(a))Log.i("MuseX04G","Pairing RX action="+a+" encrypted="+encrypted);
        if(!encrypted && a.equals("pairing_client_hello")) { send(pairing.hello(c),false);blocked=true; }
        else if(!encrypted && a.equals("pairing_encrypted")) handle(pairing.decrypt(c),true);
        else if(a.equals("get_device_info")) send(pairing.info().put("type","device_info").put("version",Store.VERSION).put("build_sha","3229892").put("network_ready",link.online()),false);
        else if(!encrypted) { if(!blocked)sendPlainError("error_encryption_required"); }
        else if(a.equals("pairing_client_finished")) { pairing.confirm(c);sendStatus("pairing_confirmed",new JSONObject().put("sdk_token",store.sdkToken())); }
        else if(!pairing.confirmed()) sendStatus("error_pairing_confirm_required",new JSONObject());
        else if(a.equals("wifi_scan")) {
            org.json.JSONArray networks=new org.json.JSONArray();
            if(link.online())networks.put(new JSONObject().put("ssid","the current connection").put("rssi",-30).put("secure",false));
            send(new JSONObject().put("type","wifi_scan_result").put("networks",networks),true);
        } else if(a.equals("provision_v2")) {
            if(busy) {sendStatus("error_operation_in_progress",new JSONObject());return;}
            if(!(c.opt("ssid") instanceof String) || !(c.opt("password") instanceof String) || c.optString("access_token").isEmpty() || c.optString("refresh_token").isEmpty() || !c.optString("token_type").equals("device")) {sendStatus("error_missing_credentials",new JSONObject());return;}
            if(!link.online()) {sendStatus("wifi_failed",new JSONObject());return;}
            busy=true; int attempt=generation;
            sendStatus("wifi_connecting",new JSONObject());sendStatus("wifi_connected",new JSONObject());
            link.provision(c,(p,error)->worker.post(()->{
                if(closed || attempt!=generation || !pairing.confirmed())return;
                busy=false;
                try {
                    if(error!=null){sendStatus("auth_failed",new JSONObject());return;}
                    store.save(p);sendStatus("auth_ok",new JSONObject());status.accept("Paired; connecting to Muse");link.reconnect();worker.postDelayed(this::close,3000);
                } catch(Exception e){status.accept("Pairing save failed");}
            }));
        } else sendStatus("error_unknown_action",new JSONObject());
    }
    void sendStatus(String s,JSONObject obj) throws Exception {Log.i("MuseX04G","Pairing TX status="+s);send(obj.put("type","status").put("status",s),true);}
    void sendPlainError(String error) {outgoing.add(Pairing.utf(error));pump();}
    void send(JSONObject obj,boolean encrypt) throws Exception {
        byte[] data=Pairing.utf((encrypt?pairing.encrypt(obj):obj).toString());
        int usable=Math.min(160,mtu-3)-3,count=Math.max(1,(data.length+usable-1)/usable);
        if(count>255)throw new IllegalArgumentException("too many BLE chunks");
        Log.i("MuseX04G","Pairing TX type="+obj.optString("type")+" encrypted="+encrypt+" bytes="+data.length+" chunks="+count);
        for(int i=0;i<count;i++){int n=Math.min(usable,data.length-i*usable);byte[] p=new byte[n+3];p[0]=(byte)254;p[1]=(byte)i;p[2]=(byte)count;System.arraycopy(data,i*usable,p,3,n);outgoing.add(p);}
        pump();
    }
    void pump() {
        if(closed || sending || !subscribed || peer==null || outgoing.isEmpty())return;
        tx.setValue(outgoing.remove());sending=server.notifyCharacteristicChanged(peer,tx,false);
        if(!sending){outgoing.clear();status.accept("BLE notification refused");}
    }
    void close() {
        if(Looper.myLooper()!=worker.getLooper()) {worker.post(this::close);return;}
        if(closed)return;closed=true;generation++;pairing.clear();outgoing.clear();incoming.reset();
        if(advertiser!=null)advertiser.stopAdvertising(advertiseCallback);
        if(server!=null)server.close();
        String old=store.prefs.getString("bluetooth_name",null);
        if(old!=null && store.name.equals(adapter.getName()))adapter.setName(old);
        thread.quitSafely();
    }
}
