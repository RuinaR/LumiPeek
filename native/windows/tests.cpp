// TEST BUILD ONLY. These exports and pause points do not exist in the production DLL.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <winioctl.h>
#include <jni.h>
#include <cstring>
#include <string>
#include <vector>

namespace {
std::wstring fixtureRoot;
struct Pause {
    HANDLE entered = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    HANDLE release = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    volatile LONG stage = 0;
    ~Pause() { if (entered) CloseHandle(entered); if (release) CloseHandle(release); }
};
Pause& pauseState() { static Pause state; return state; }
std::wstring text(JNIEnv* env, jstring s) {
    if (!s) return L"";
    jsize n = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (!chars) return L"";
    std::wstring value(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(n));
    env->ReleaseStringChars(s, chars);
    return value;
}
bool allowed(const std::wstring& path) {
    return !fixtureRoot.empty() && path.size() > fixtureRoot.size()
        && path.compare(0, fixtureRoot.size() + 1, fixtureRoot + L"\\") == 0
        && path.find(L"..") == std::wstring::npos && path.find(L'/') == std::wstring::npos;
}
}
void fixturePause(int stage) {
    Pause& state = pauseState();
    if (InterlockedCompareExchange(&state.stage, 0, stage) == stage) {
        SetEvent(state.entered);
        WaitForSingleObject(state.release, 5000);
    }
}
extern "C" JNIEXPORT jboolean JNICALL Java_io_lumipeek_core_WindowsFixtures_register0(JNIEnv* env, jclass, jstring root) {
    std::wstring value = text(env, root);
    wchar_t cwd[32768]{};
    DWORD length = GetCurrentDirectoryW(32768, cwd);
    if (!length || length >= 32768 || !fixtureRoot.empty()) return JNI_FALSE;
    std::wstring prefix = std::wstring(cwd) + L"\\build\\windows-";
    if (value.compare(0, prefix.size(), prefix) || value.find(L"..") != std::wstring::npos) return JNI_FALSE;
    fixtureRoot = value;
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_io_lumipeek_core_WindowsFixtures_junction0(
        JNIEnv* env, jclass, jstring link, jstring target) {
    std::wstring from = text(env, link), to = text(env, target);
    if (!allowed(from) || !allowed(to) || !CreateDirectoryW(from.c_str(), nullptr)) return JNI_FALSE;
    HANDLE h = CreateFileW(from.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) return JNI_FALSE;
    std::wstring substitute = L"\\??\\" + to;
    size_t subBytes = substitute.size() * sizeof(wchar_t), printBytes = to.size() * sizeof(wchar_t);
    std::vector<unsigned char> buffer(16 + subBytes + printBytes + 4, 0);
    DWORD tag = IO_REPARSE_TAG_MOUNT_POINT;
    std::memcpy(buffer.data(), &tag, sizeof(tag));
    auto* fields = reinterpret_cast<USHORT*>(buffer.data() + 4);
    fields[0] = static_cast<USHORT>(buffer.size() - 8); // ReparseDataLength
    fields[1] = 0; fields[2] = 0; fields[3] = static_cast<USHORT>(subBytes);
    fields[4] = static_cast<USHORT>(subBytes + 2); fields[5] = static_cast<USHORT>(printBytes);
    std::memcpy(buffer.data() + 16, substitute.data(), subBytes);
    std::memcpy(buffer.data() + 16 + subBytes + 2, to.data(), printBytes);
    DWORD returned = 0;
    BOOL ok = DeviceIoControl(h, FSCTL_SET_REPARSE_POINT, buffer.data(), static_cast<DWORD>(buffer.size()), nullptr, 0, &returned, nullptr);
    CloseHandle(h);
    return ok ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_io_lumipeek_core_WindowsFixtures_arm0(JNIEnv*, jclass, jint stage) {
    Pause& state = pauseState(); ResetEvent(state.entered); ResetEvent(state.release);
    InterlockedExchange(&state.stage, stage);
}
extern "C" JNIEXPORT jboolean JNICALL Java_io_lumipeek_core_WindowsFixtures_await0(JNIEnv*, jclass) {
    return WaitForSingleObject(pauseState().entered, 3000) == WAIT_OBJECT_0 ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_io_lumipeek_core_WindowsFixtures_release0(JNIEnv*, jclass) {
    SetEvent(pauseState().release);
}
extern "C" JNIEXPORT jboolean JNICALL Java_io_lumipeek_core_WindowsFixtures_offline0(JNIEnv* env, jclass, jstring path, jboolean set) {
    std::wstring value = text(env, path);
    if (!allowed(value)) return JNI_FALSE;
    DWORD old = GetFileAttributesW(value.c_str());
    if (old == INVALID_FILE_ATTRIBUTES) return JNI_FALSE;
    DWORD attrs = set ? old | FILE_ATTRIBUTE_OFFLINE : old & ~FILE_ATTRIBUTE_OFFLINE;
    return SetFileAttributesW(value.c_str(), attrs) ? JNI_TRUE : JNI_FALSE;
}
