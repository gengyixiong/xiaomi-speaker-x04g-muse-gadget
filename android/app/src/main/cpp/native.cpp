#include <jni.h>
#include <array>
#include <vector>
#include <string>
#include <android/bitmap.h>
extern "C" {
#include "muse_pixel.h"
}
#include <xplat/noise/core/ClientSession.h>
#include <xplat/noise/core/PsaCryptoBackend.h>
using namespace musegadgets::noise::core;

struct Session {
    PsaCryptoBackend crypto;
    ClientSession noise{crypto};
    std::vector<uint8_t> svc=std::vector<uint8_t>(512*1024);
    std::vector<uint8_t> env=std::vector<uint8_t>(512*1024);
    std::array<uint8_t,ClientSession::kMaxOutboundWebSocketPayloadSize> ws{};
};
static Session& session(jlong h) { return *reinterpret_cast<Session*>(h); }
static bool check(JNIEnv* e, Status s) {
    if(s.ok()) return true;
    e->ThrowNew(e->FindClass("java/io/IOException"),s.str());
    return false;
}
static jbyteArray bytes(JNIEnv* e, ConstByteSpan data) {
    auto a=e->NewByteArray(data.size());
    if(a && !data.empty()) e->SetByteArrayRegion(a,0,data.size(),reinterpret_cast<const jbyte*>(data.data()));
    return a;
}
static std::vector<uint8_t> input(JNIEnv* e, jbyteArray a) {
    std::vector<uint8_t> out(e->GetArrayLength(a));
    e->GetByteArrayRegion(a,0,out.size(),reinterpret_cast<jbyte*>(out.data()));
    return out;
}
static ConstByteSpan span(const std::vector<uint8_t>& a) { return {a.data(),a.size()}; }
static jobjectArray drain(JNIEnv* e, Session& s) {
    std::vector<std::vector<uint8_t>> parts;
    while(s.noise.HasOutboundWebSocketPayload()) {
        auto r=s.noise.WriteNextOutboundWebSocketPayload(ByteSpan(s.ws));
        if(!check(e,r.status())) return nullptr;
        parts.emplace_back(s.ws.begin(),s.ws.begin()+r.size());
    }
    auto result=e->NewObjectArray(parts.size(),e->FindClass("[B"),nullptr);
    for(size_t i=0;result && i<parts.size();++i) {
        auto a=bytes(e,span(parts[i]));
        e->SetObjectArrayElement(result,i,a);
        e->DeleteLocalRef(a);
    }
    return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_io_muse_x04g_Native_create(JNIEnv*,jclass) {
    return reinterpret_cast<jlong>(new Session);
}
extern "C" JNIEXPORT void JNICALL Java_io_muse_x04g_Native_destroy(JNIEnv*,jclass,jlong h) { delete reinterpret_cast<Session*>(h); }
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_muse_x04g_Native_hello(JNIEnv* e,jclass,jlong h) {
    auto& s=session(h);
    auto r=s.noise.WriteHandshakeMessage1(ByteSpan(s.ws));
    return check(e,r.status()) ? bytes(e,{s.ws.data(),r.size()}) : nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_muse_x04g_Native_finish(JNIEnv* e,jclass,jlong h,jbyteArray a) {
    auto& s=session(h); auto in=input(e,a); size_t n=0;
    if(!check(e,s.noise.ReadHandshakeMessage2(span(in),ByteSpan(s.svc.data(),s.svc.size()),n))) return nullptr;
    auto r=s.noise.WriteHandshakeMessage3({},ByteSpan(s.ws));
    return check(e,r.status()) ? bytes(e,{s.ws.data(),r.size()}) : nullptr;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_io_muse_x04g_Native_request(JNIEnv* e,jclass,jlong h,jlong id,jstring path,jstring requestId,jbyteArray a,jboolean end) {
    auto& s=session(h); auto in=input(e,a);
    const char* p=e->GetStringUTFChars(path,nullptr);
    const char* rid=e->GetStringUTFChars(requestId,nullptr);
    HeaderView hdrs[]={{StringView("Content-Type"),StringView("application/json")},{StringView("x-app-id"),StringView("musegadget")},{StringView("x-request-id"),StringView(rid)}};
    ApplicationRequestView req;
    req.verb=StringView("POST"); req.path=StringView(p); req.headers=Span<const HeaderView>(hdrs,3); req.body=span(in); req.end_body=end;
    auto r=s.noise.StartOutboundApplicationRequest(ServiceType::Daemon,id,req,{s.svc.data(),s.svc.size()},{s.env.data(),s.env.size()});
    e->ReleaseStringUTFChars(path,p);
    e->ReleaseStringUTFChars(requestId,rid);
    return check(e,r.status()) ? drain(e,s) : nullptr;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_io_muse_x04g_Native_body(JNIEnv* e,jclass,jlong h,jlong id,jbyteArray a,jboolean end) {
    auto& s=session(h); auto in=input(e,a);
    auto r=s.noise.StartOutboundBodyChunk(ServiceType::Daemon,id,{span(in),bool(end)},{s.svc.data(),s.svc.size()},{s.env.data(),s.env.size()});
    return check(e,r.status()) ? drain(e,s) : nullptr;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_io_muse_x04g_Native_reset(JNIEnv* e,jclass,jlong h,jlong id) {
    auto& s=session(h);
    auto r=s.noise.StartOutboundReset(ServiceType::Daemon,id,{ResetCode::Cancelled,StringView("cancelled")},{s.svc.data(),s.svc.size()},{s.env.data(),s.env.size()});
    return check(e,r.status()) ? drain(e,s) : nullptr;
}
extern "C" JNIEXPORT jobject JNICALL Java_io_muse_x04g_Native_receive(JNIEnv* e,jclass,jlong h,jbyteArray a) {
    auto& s=session(h); auto in=input(e,a); std::array<HeaderView,64> headers;
    if(in.size()>s.svc.size()) { check(e,Status::ResourceExhausted()); return nullptr; }
    auto r=s.noise.ProcessInboundWebSocketPayload(span(in),{s.env.data(),s.env.size()},{s.svc.data(),s.svc.size()},Span<HeaderView>(headers));
    if(!check(e,r.status) || r.frame_status!=InboundFrameStatus::Complete) return nullptr;
    auto f=r.frame; ConstByteSpan data; bool end=false; int status=0;
    if(f.kind==ServiceFrameKind::Response) { data=f.response.body; end=f.response.end_body; status=f.response.status; }
    else if(f.kind==ServiceFrameKind::BodyChunk) { data=f.body_chunk.data; end=f.body_chunk.end_body; }
    else if(f.kind==ServiceFrameKind::Reset) { end=true; status=-(int)f.reset.code; }
    auto cls=e->FindClass("io/muse/x04g/Native$Frame");
    auto ctor=e->GetMethodID(cls,"<init>","(JIIZ[B)V");
    return e->NewObject(cls,ctor,(jlong)f.stream_id,(jint)f.kind,status,(jboolean)end,bytes(e,data));
}
extern "C" JNIEXPORT void JNICALL Java_io_muse_x04g_Native_avatar(JNIEnv* e,jclass,jobject bitmap,jint mode,jfloat t,jfloat modeTime,jfloat level,jfloat happy) {
    AndroidBitmapInfo info{};void* pixels=nullptr;
    if(AndroidBitmap_getInfo(e,bitmap,&info)!=0 || info.format!=ANDROID_BITMAP_FORMAT_RGB_565 || info.width!=info.height || info.width>512 || mode<0 || mode>=MUSE_MODE_COUNT) {
        e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"invalid avatar bitmap or pose");return;
    }
    if(AndroidBitmap_lockPixels(e,bitmap,&pixels)!=0)return;
    muse_pose_t pose{static_cast<muse_mode_t>(mode),t,modeTime,level,happy};
    muse_pixel_set_size(info.width);muse_pixel_render(&pose);
    muse_pixel_scale(static_cast<uint16_t*>(pixels),info.stride/2,0,info.width-1,0,info.height-1);
    AndroidBitmap_unlockPixels(e,bitmap);
}
