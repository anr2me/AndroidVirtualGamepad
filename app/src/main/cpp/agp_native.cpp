// Native bridge: evdev reading, uinput creation, event writing.
// All functions return a negative errno on failure where an int is returned.
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <vector>
#include <linux/input.h>
#include <linux/uinput.h>

#define JFUNC(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_com_avgamepad_nativebridge_NativeInput_##name

static std::vector<jint> toVec(JNIEnv* env, jintArray arr) {
    std::vector<jint> v;
    if (!arr) return v;
    jsize n = env->GetArrayLength(arr);
    v.resize(n);
    if (n > 0) env->GetIntArrayRegion(arr, 0, n, v.data());
    return v;
}

static int openUinput() {
    static const char* paths[] = {"/dev/uinput", "/dev/input/uinput", "/dev/misc/uinput"};
    int best = -ENOENT;
    for (const char* p : paths) {
        int fd = open(p, O_RDWR | O_NONBLOCK | O_CLOEXEC);   // read side: force feedback requests
        if (fd >= 0) return fd;
        if (errno != ENOENT) best = -errno;
    }
    return best;
}

JFUNC(jint, openDevice)(JNIEnv* env, jclass, jstring jpath, jboolean write) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    int fd = open(path, (write ? O_RDWR : O_RDONLY) | O_NONBLOCK | O_CLOEXEC);
    int err = errno;
    env->ReleaseStringUTFChars(jpath, path);
    return fd >= 0 ? fd : -err;
}

JFUNC(void, closeFd)(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) close(fd);
}

JFUNC(jint, grab)(JNIEnv*, jclass, jint fd, jboolean on) {
    int r = ioctl(fd, EVIOCGRAB, (void*)(intptr_t)(on ? 1 : 0));
    return r < 0 ? -errno : 0;
}

JFUNC(jstring, deviceName)(JNIEnv* env, jclass, jint fd) {
    char name[256];
    memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) return nullptr;
    for (char* c = name; *c; ++c)
        if ((unsigned char)*c < 0x20 || (unsigned char)*c > 0x7e) *c = '?';
    return env->NewStringUTF(name);
}

JFUNC(jintArray, deviceIds)(JNIEnv* env, jclass, jint fd) {
    struct input_id id;
    if (ioctl(fd, EVIOCGID, &id) < 0) return nullptr;
    jint v[4] = {id.bustype, id.vendor, id.product, id.version};
    jintArray res = env->NewIntArray(4);
    env->SetIntArrayRegion(res, 0, 4, v);
    return res;
}

JFUNC(jintArray, capabilities)(JNIEnv* env, jclass, jint fd, jint type) {
    unsigned char bits[KEY_MAX / 8 + 1];
    memset(bits, 0, sizeof(bits));
    int maxCode = (type == EV_KEY) ? KEY_MAX : (type == EV_ABS) ? ABS_MAX : 0x1f;
    std::vector<jint> out;
    if (ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits) >= 0) {
        for (int c = 0; c <= maxCode && c / 8 < (int)sizeof(bits); ++c)
            if (bits[c / 8] & (1 << (c % 8))) out.push_back(c);
    }
    jintArray res = env->NewIntArray((jsize)out.size());
    if (!out.empty()) env->SetIntArrayRegion(res, 0, (jsize)out.size(), out.data());
    return res;
}

// returns {value, min, max, fuzz, flat} or null
JFUNC(jintArray, absInfo)(JNIEnv* env, jclass, jint fd, jint code) {
    struct input_absinfo ai;
    if (ioctl(fd, EVIOCGABS(code), &ai) < 0) return nullptr;
    jint v[5] = {ai.value, ai.minimum, ai.maximum, ai.fuzz, ai.flat};
    jintArray res = env->NewIntArray(5);
    env->SetIntArrayRegion(res, 0, 5, v);
    return res;
}

// Waits up to timeoutMs, then reads events as (type, code, value) triples.
// Returns the event count, 0 on timeout, -1 when the device is gone.
JFUNC(jint, readEvents)(JNIEnv* env, jclass, jint fd, jintArray out, jint timeoutMs) {
    jsize cap = env->GetArrayLength(out) / 3;
    if (cap <= 0) return -1;
    struct pollfd pfd = {fd, POLLIN, 0};
    int pr = poll(&pfd, 1, timeoutMs);
    if (pr == 0) return 0;
    if (pr < 0) return errno == EINTR ? 0 : -1;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    struct input_event evs[64];
    int maxn = cap < 64 ? (int)cap : 64;
    ssize_t n = read(fd, evs, sizeof(struct input_event) * maxn);
    if (n < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -1;
    int cnt = (int)(n / sizeof(struct input_event));
    jint tmp[64 * 3];
    for (int i = 0; i < cnt; ++i) {
        tmp[i * 3] = evs[i].type;
        tmp[i * 3 + 1] = evs[i].code;
        tmp[i * 3 + 2] = evs[i].value;
    }
    env->SetIntArrayRegion(out, 0, cnt * 3, tmp);
    return cnt;
}

// Writes (type, code, value) triples in one write() call.
JFUNC(jint, writeEvents)(JNIEnv* env, jclass, jint fd, jintArray arr, jint count) {
    if (count <= 0 || count > 256) return 0;
    jint tmp[256 * 3];
    env->GetIntArrayRegion(arr, 0, count * 3, tmp);
    struct input_event evs[256];
    memset(evs, 0, sizeof(evs));
    for (int i = 0; i < count; ++i) {
        evs[i].type = (__u16)tmp[i * 3];
        evs[i].code = (__u16)tmp[i * 3 + 1];
        evs[i].value = tmp[i * 3 + 2];
    }
    ssize_t w = write(fd, evs, sizeof(struct input_event) * count);
    return w < 0 ? -errno : (jint)(w / sizeof(struct input_event));
}

JFUNC(jint, uinputProbe)(JNIEnv*, jclass) {
    int fd = openUinput();
    if (fd < 0) return fd;
    int r = ioctl(fd, UI_SET_EVBIT, EV_KEY);
    int err = errno;
    close(fd);
    return r < 0 ? -err : 0;
}

JFUNC(jint, uinputCreate)(JNIEnv* env, jclass, jstring jname, jint bus, jint vendor,
                          jint product, jint version, jintArray jkeys, jintArray jabs,
                          jintArray jmin, jintArray jmax, jintArray jfuzz, jintArray jflat,
                          jboolean ff) {
    int fd = openUinput();
    if (fd < 0) return fd;
#define CHECK(expr) do { if ((expr) < 0) { int e = errno; close(fd); return -e; } } while (0)

    CHECK(ioctl(fd, UI_SET_EVBIT, EV_KEY));
    CHECK(ioctl(fd, UI_SET_EVBIT, EV_SYN));

    std::vector<jint> keys = toVec(env, jkeys), abs = toVec(env, jabs);
    if (!abs.empty()) CHECK(ioctl(fd, UI_SET_EVBIT, EV_ABS));
    // Force feedback is optional. A failure here must not stop the gamepad.
    bool ffOk = false;
    if (ff) ffOk = ioctl(fd, UI_SET_EVBIT, EV_FF) >= 0 && ioctl(fd, UI_SET_FFBIT, FF_RUMBLE) >= 0;
    std::vector<jint> mn = toVec(env, jmin), mx = toVec(env, jmax);
    std::vector<jint> fz = toVec(env, jfuzz), fl = toVec(env, jflat);
    for (jint k : keys) CHECK(ioctl(fd, UI_SET_KEYBIT, k));
    for (jint a : abs) CHECK(ioctl(fd, UI_SET_ABSBIT, a));

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    const char* name = env->GetStringUTFChars(jname, nullptr);
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "%s", name);
    env->ReleaseStringUTFChars(jname, name);
    dev.id.bustype = (__u16)bus;
    dev.id.vendor = (__u16)vendor;
    dev.id.product = (__u16)product;
    dev.id.version = (__u16)version;
    if (ffOk) dev.ff_effects_max = 16;
    for (size_t i = 0; i < abs.size() && i < mn.size() && i < mx.size(); ++i) {
        int c = abs[i];
        if (c < 0 || c >= ABS_CNT) continue;
        dev.absmin[c] = mn[i];
        dev.absmax[c] = mx[i];
        dev.absfuzz[c] = i < fz.size() ? fz[i] : 0;
        dev.absflat[c] = i < fl.size() ? fl[i] : 0;
    }
    if (write(fd, &dev, sizeof(dev)) != (ssize_t)sizeof(dev)) {
        int e = errno ? errno : EIO;
        close(fd);
        return -e;
    }
    CHECK(ioctl(fd, UI_DEV_CREATE));
#undef CHECK
    return fd;
}

JFUNC(void, uinputDestroy)(JNIEnv*, jclass, jint fd) {
    if (fd < 0) return;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

// ---- Force feedback (rumble) ----

// Reads requests that Android sends to the uinput device. Each record has 5 ints:
//   {1, effectId, strong, weak, lengthMs}  effect uploaded
//   {2, effectId, 0, 0, 0}                 effect erased
//   {3, effectId, count, 0, 0}             play (count > 0) or stop (count = 0)
//   {4, gain, 0, 0, 0}                     gain 0..65535
// Upload and erase requests are completed here, because Android waits for them.
// Returns the record count, 0 on timeout, -1 on error.
JFUNC(jint, ffRead)(JNIEnv* env, jclass, jint fd, jintArray out, jint timeoutMs) {
    jsize cap = env->GetArrayLength(out) / 5;
    if (cap < 16) return -1;
    struct pollfd pfd = {fd, POLLIN, 0};
    int pr = poll(&pfd, 1, timeoutMs);
    if (pr == 0) return 0;
    if (pr < 0) return errno == EINTR ? 0 : -1;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    struct input_event evs[16];
    ssize_t n = read(fd, evs, sizeof(evs));
    if (n < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -1;
    int cnt = (int)(n / sizeof(struct input_event));
    std::vector<jint> res;
    for (int i = 0; i < cnt; ++i) {
        const struct input_event& ev = evs[i];
        if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
            struct uinput_ff_upload up;
            memset(&up, 0, sizeof(up));
            up.request_id = ev.value;
            if (ioctl(fd, UI_BEGIN_FF_UPLOAD, &up) < 0) continue;
            int strong = 0, weak = 0;
            if (up.effect.type == FF_RUMBLE) {
                strong = up.effect.u.rumble.strong_magnitude;
                weak = up.effect.u.rumble.weak_magnitude;
            } else if (up.effect.type == FF_PERIODIC) {
                int m = up.effect.u.periodic.magnitude;
                if (m < 0) m = -m;
                strong = weak = m * 2;
            }
            res.insert(res.end(), {1, (jint)up.effect.id, strong, weak, (jint)up.effect.replay.length});
            up.retval = 0;
            ioctl(fd, UI_END_FF_UPLOAD, &up);
        } else if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
            struct uinput_ff_erase er;
            memset(&er, 0, sizeof(er));
            er.request_id = ev.value;
            if (ioctl(fd, UI_BEGIN_FF_ERASE, &er) < 0) continue;
            res.insert(res.end(), {2, (jint)er.effect_id, 0, 0, 0});
            er.retval = 0;
            ioctl(fd, UI_END_FF_ERASE, &er);
        } else if (ev.type == EV_FF) {
            if (ev.code == FF_GAIN) res.insert(res.end(), {4, ev.value, 0, 0, 0});
            else res.insert(res.end(), {3, (jint)ev.code, ev.value, 0, 0});
        }
    }
    if (!res.empty()) env->SetIntArrayRegion(out, 0, (jsize)res.size(), res.data());
    return (jint)(res.size() / 5);
}

// Uploads a rumble effect to a real device. id = -1 creates a new effect. Returns the effect id or -errno.
JFUNC(jint, ffUpload)(JNIEnv*, jclass, jint fd, jint id, jint strong, jint weak, jint lengthMs) {
    struct ff_effect e;
    memset(&e, 0, sizeof(e));
    e.type = FF_RUMBLE;
    e.id = (__s16)id;
    e.u.rumble.strong_magnitude = (__u16)strong;
    e.u.rumble.weak_magnitude = (__u16)weak;
    e.replay.length = (__u16)(lengthMs > 0xffff ? 0xffff : lengthMs);
    e.replay.delay = 0;
    if (ioctl(fd, EVIOCSFF, &e) < 0) return -errno;
    return e.id;
}

JFUNC(jint, ffErase)(JNIEnv*, jclass, jint fd, jint id) {
    return ioctl(fd, EVIOCRMFF, id) < 0 ? -errno : 0;
}

JFUNC(jboolean, ffSupported)(JNIEnv*, jclass, jint fd) {
    unsigned char bits[FF_MAX / 8 + 1];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_FF, sizeof(bits)), bits) < 0) return JNI_FALSE;
    return (bits[FF_RUMBLE / 8] & (1 << (FF_RUMBLE % 8))) ? JNI_TRUE : JNI_FALSE;
}
