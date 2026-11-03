// Read-only Windows x64 JNI boundary. No process, UI or network operations.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <winternl.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <string>
#include <utility>
#include <vector>
#ifdef LUMIPEEK_TEST
void fixturePause(int stage);
#else
static void fixturePause(int) {}
#endif

namespace {
constexpr DWORD MAX_BYTES = 25 * 1024 * 1024;
constexpr DWORD CLOUD_FLAGS = FILE_ATTRIBUTE_OFFLINE | 0x00040000 | 0x00400000;
struct Denied { const char* code; };
[[noreturn]] void deny(const char* code = "PATH_DENIED") { throw Denied{code}; }
struct Handle {
    HANDLE h;
    explicit Handle(HANDLE value) : h(value) {}
    ~Handle() { if (h != INVALID_HANDLE_VALUE) CloseHandle(h); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    Handle(Handle&& other) noexcept : h(std::exchange(other.h, INVALID_HANDLE_VALUE)) {}
    Handle& operator=(Handle&&) = delete;
};
struct Budget {
    JNIEnv* env;
    jobject thread = nullptr;
    jmethodID interrupted = nullptr;
    ULONGLONG start = GetTickCount64(), ms;
    Budget(JNIEnv* e, jlong timeout) : env(e), ms(static_cast<ULONGLONG>(timeout)) {
        if (timeout < 1 || timeout > 60000) deny();
        jclass cls = env->FindClass("java/lang/Thread");
        if (!cls) deny();
        jmethodID current = env->GetStaticMethodID(cls, "currentThread", "()Ljava/lang/Thread;");
        if (!current) { env->DeleteLocalRef(cls); deny(); }
        thread = env->CallStaticObjectMethod(cls, current);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(cls); deny(); }
        interrupted = env->GetMethodID(cls, "isInterrupted", "()Z");
        env->DeleteLocalRef(cls);
        if (!thread || !interrupted) deny();
    }
    ~Budget() { if (thread) env->DeleteLocalRef(thread); }
    void check() const {
        jboolean value = env->CallBooleanMethod(thread, interrupted);
        if (env->ExceptionCheck()) deny();
        if (value) deny("CANCELLED");
        if (GetTickCount64() - start >= ms) deny("TIMEOUT");
    }
};
std::wstring string(JNIEnv* env, jstring value) {
    if (!value) deny();
    jsize count = env->GetStringLength(value);
    if (count < 1 || count > 30000) deny();
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) deny();
    std::wstring result;
    try { result.assign(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(count)); }
    catch (...) { env->ReleaseStringChars(value, chars); throw; }
    env->ReleaseStringChars(value, chars);
    return result;
}
bool sameText(const std::wstring& a, const std::wstring& b) {
    return CompareStringOrdinal(a.c_str(), static_cast<int>(a.size()), b.c_str(),
        static_cast<int>(b.size()), TRUE) == CSTR_EQUAL;
}
void component(const std::wstring& name) {
    if (name.empty() || name.size() > 255 || name == L"." || name == L".."
            || name.back() == L'.' || name.back() == L' ') deny();
    for (size_t i = 0; i < name.size(); ++i) {
        wchar_t c = name[i];
        if (c < 32 || wcschr(L"<>:\"/\\|?*", c)) deny();
        if (c >= 0xD800 && c <= 0xDBFF) {
            if (++i >= name.size() || name[i] < 0xDC00 || name[i] > 0xDFFF) deny();
        } else if (c >= 0xDC00 && c <= 0xDFFF) deny();
    }
    std::wstring base = name.substr(0, name.find(L'.'));
    if (sameText(base, L"CON") || sameText(base, L"PRN") || sameText(base, L"AUX")
            || sameText(base, L"NUL") || sameText(base, L"CLOCK$")) deny();
    if (base.size() == 4 && (sameText(base.substr(0, 3), L"COM") || sameText(base.substr(0, 3), L"LPT"))
            && base[3] >= L'0' && base[3] <= L'9') deny();
}
std::vector<std::wstring> split(const std::wstring& path) {
    std::vector<std::wstring> parts;
    size_t start = 0;
    for (;;) {
        size_t end = path.find(L'\\', start);
        std::wstring part = path.substr(start, end == std::wstring::npos ? end : end - start);
        component(part); parts.push_back(std::move(part));
        if (parts.size() > 64) deny("FILE_LIMIT");
        if (end == std::wstring::npos) break;
        start = end + 1;
    }
    return parts;
}
std::wstring finalPath(HANDLE h) {
    std::vector<wchar_t> buffer(32768);
    DWORD size = GetFinalPathNameByHandleW(h, buffer.data(), static_cast<DWORD>(buffer.size()), VOLUME_NAME_GUID);
    if (!size || size >= buffer.size()) deny();
    return std::wstring(buffer.data(), size);
}
std::array<jlong, 3> key(HANDLE h) {
    FILE_ID_INFO info{};
    if (!GetFileInformationByHandleEx(h, FileIdInfo, &info, sizeof(info))) deny();
    std::array<jlong, 3> result{};
    static_assert(sizeof(info.FileId.Identifier) == 16);
    result[0] = static_cast<jlong>(info.VolumeSerialNumber);
    std::memcpy(result.data() + 1, info.FileId.Identifier, 16);
    return result;
}
BY_HANDLE_FILE_INFORMATION validate(HANDLE h, bool directory) {
    if (GetFileType(h) != FILE_TYPE_DISK) deny();
    FILE_ATTRIBUTE_TAG_INFO tag{};
    FILE_STANDARD_INFO standard{};
    BY_HANDLE_FILE_INFORMATION info{};
    if (!GetFileInformationByHandleEx(h, FileAttributeTagInfo, &tag, sizeof(tag))
            || !GetFileInformationByHandleEx(h, FileStandardInfo, &standard, sizeof(standard))
            || !GetFileInformationByHandle(h, &info)) deny();
    if (tag.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT || tag.ReparseTag) deny("LINK_DENIED");
    if (tag.FileAttributes & CLOUD_FLAGS || standard.DeletePending || !!standard.Directory != directory) deny();
    if (!directory && (info.nNumberOfLinks != 1 || standard.NumberOfLinks != 1)) deny("LINK_DENIED");
    if (!directory && (info.nFileSizeHigh || !info.nFileSizeLow || info.nFileSizeLow > MAX_BYTES)) deny("FILE_LIMIT");
    return info;
}
Handle child(HANDLE parent, const std::wstring& name, bool directory, bool data, const Budget& budget) {
    budget.check(); component(name);
    UNICODE_STRING text{};
    text.Buffer = const_cast<PWSTR>(name.data());
    text.Length = static_cast<USHORT>(name.size() * sizeof(wchar_t)); text.MaximumLength = text.Length;
    OBJECT_ATTRIBUTES oa{};
    oa.Length = sizeof(oa); oa.RootDirectory = parent; oa.ObjectName = &text;
    oa.Attributes = OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE;
    IO_STATUS_BLOCK status{};
    HANDLE opened = INVALID_HANDLE_VALUE;
    ACCESS_MASK access = FILE_READ_ATTRIBUTES | SYNCHRONIZE;
    if (directory) access |= data ? FILE_LIST_DIRECTORY : FILE_TRAVERSE;
    else if (data) access |= FILE_READ_DATA;
    NTSTATUS rc = NtCreateFile(&opened, access, &oa, &status, nullptr, 0,
        directory ? FILE_SHARE_READ | FILE_SHARE_WRITE : FILE_SHARE_READ,
        FILE_OPEN, FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT
            | (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE | FILE_OPEN_NO_RECALL), nullptr, 0);
    if (rc < 0) deny(static_cast<ULONG>(rc) == 0xC000050B ? "LINK_DENIED" : "PATH_DENIED");
    Handle handle(opened);
    validate(handle.h, directory);
    std::wstring parentPath = finalPath(parent);
    if (parentPath.back() != L'\\') parentPath += L'\\';
    // Reject aliases and changed ancestry. Every parent is still pinned by its handle.
    if (!sameText(finalPath(handle.h), parentPath + name)) deny();
    budget.check(); return handle;
}
struct Root {
    std::vector<Handle> pins;
    HANDLE get() const { return pins.back().h; }
};
Root openRoot(const std::wstring& path, bool list, const Budget& budget) {
    // Reject UNC/device/drive-relative paths and volume-wide grants before opening.
    if (path.size() < 4 || !((path[0] >= L'A' && path[0] <= L'Z') || (path[0] >= L'a' && path[0] <= L'z'))
            || path[1] != L':' || path[2] != L'\\') deny();
    auto parts = split(path.substr(3));
    std::wstring drive = path.substr(0, 3);
    // Resolve the DOS drive mapping locally, before opening anything. A drive-letter
    // remap between GetDriveType and CreateFile must never redirect us to SMB.
    std::vector<wchar_t> device(32768);
    std::wstring letter = path.substr(0, 2);
    if (!QueryDosDeviceW(letter.c_str(), device.data(), static_cast<DWORD>(device.size()))) deny();
    std::wstring target(device.data()), prefix = L"\\Device\\HarddiskVolume";
    if (target.compare(0, prefix.size(), prefix) || target.size() == prefix.size()) deny();
    for (size_t i = prefix.size(); i < target.size(); ++i)
        if (target[i] < L'0' || target[i] > L'9') deny();
    if (GetDriveTypeW(drive.c_str()) != DRIVE_FIXED) deny();
    budget.check();
    Root root;
    std::wstring extended = L"\\\\?\\GLOBALROOT" + target + L"\\";
    HANDLE raw = CreateFileW(extended.c_str(), FILE_READ_ATTRIBUTES | FILE_TRAVERSE | SYNCHRONIZE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (raw == INVALID_HANDLE_VALUE) deny();
    Handle volumeHandle(raw);
    validate(raw, true);
    wchar_t fs[32]{};
    if (!GetVolumeInformationByHandleW(raw, nullptr, 0, nullptr, nullptr, nullptr, fs, 32) || wcscmp(fs, L"NTFS") != 0) deny();
    std::wstring volume = finalPath(raw);
    // Reject SUBST mappings to subdirectories (not a volume root).
    if (volume.size() != 49 || volume.compare(0, 11, L"\\\\?\\Volume{") != 0 || volume.substr(47) != L"}\\") deny();
    root.pins.push_back(std::move(volumeHandle));
    for (size_t i = 0; i < parts.size(); ++i)
        root.pins.push_back(child(root.get(), parts[i], true, list && i + 1 == parts.size(), budget));
    budget.check(); return root;
}
void expected(JNIEnv* env, jlongArray input, HANDLE h) {
    if (!input || env->GetArrayLength(input) != 3) deny();
    std::array<jlong, 3> wanted{};
    env->GetLongArrayRegion(input, 0, 3, wanted.data());
    if (env->ExceptionCheck() || key(h) != wanted) deny();
}
bool extension(const std::wstring& name) {
    size_t dot = name.find_last_of(L'.');
    if (dot == std::wstring::npos) return false;
    std::wstring suffix = name.substr(dot);
    return sameText(suffix, L".png") || sameText(suffix, L".jpg") || sameText(suffix, L".jpeg");
}
void walk(HANDLE dir, const std::wstring& prefix, bool recursive, int limit, int& visited,
        int depth, size_t& pathChars, std::vector<std::wstring>& found, const Budget& budget) {
    if (depth > 64) deny("FILE_LIMIT");
    auto buffer = std::make_unique<unsigned char[]>(65536);
    bool first = true;
    for (;;) {
        budget.check();
        BOOL ok = GetFileInformationByHandleEx(dir, first ? FileIdBothDirectoryRestartInfo : FileIdBothDirectoryInfo,
            buffer.get(), 65536);
        first = false;
        if (!ok) { if (GetLastError() == ERROR_NO_MORE_FILES) break; deny(); }
        size_t offset = 0;
        for (;;) {
            constexpr size_t header = offsetof(FILE_ID_BOTH_DIR_INFO, FileName);
            if (offset > 65536 - header) deny();
            auto* item = reinterpret_cast<FILE_ID_BOTH_DIR_INFO*>(buffer.get() + offset);
            if (item->FileNameLength % 2 || item->FileNameLength > 65536 - offset - header) deny();
            std::wstring name(item->FileName, item->FileNameLength / sizeof(wchar_t));
            if (name != L"." && name != L"..") {
                budget.check();
                if (++visited > limit) deny("FILE_LIMIT");
                if (!(item->FileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | CLOUD_FLAGS))) {
                    bool isDir = (item->FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
                    if (isDir && recursive) {
                        Handle next = child(dir, name, true, true, budget);
                        walk(next.h, prefix + name + L"\\", true, limit, visited, depth + 1, pathChars, found, budget);
                    } else if (!isDir && extension(name)) {
                        if (pathChars + prefix.size() + name.size() > 1024 * 1024) deny("FILE_LIMIT");
                        try {
                            fixturePause(5); budget.check();
                            Handle file = child(dir, name, false, false, budget);
                            pathChars += prefix.size() + name.size();
                            found.push_back(prefix + name);
                        } catch (const Denied& ex) {
                            if (std::strcmp(ex.code, "CANCELLED") == 0 || std::strcmp(ex.code, "TIMEOUT") == 0) throw;
                        }
                    }
                }
            }
            if (!item->NextEntryOffset) break;
            if (item->NextEntryOffset < header || item->NextEntryOffset > 65536 - offset) deny();
            offset += item->NextEntryOffset;
        }
    }
}
void report(JNIEnv* env, const char* code) {
    if (env->ExceptionCheck()) return;
    jclass enumClass = env->FindClass("io/lumipeek/core/Failure$Code");
    if (!enumClass) return;
    jfieldID field = env->GetStaticFieldID(enumClass, code, "Lio/lumipeek/core/Failure$Code;");
    if (!field) { env->DeleteLocalRef(enumClass); return; }
    jobject value = env->GetStaticObjectField(enumClass, field);
    jclass failure = env->FindClass("io/lumipeek/core/Failure");
    if (!failure) { env->DeleteLocalRef(value); env->DeleteLocalRef(enumClass); return; }
    jmethodID ctor = env->GetMethodID(failure, "<init>", "(Lio/lumipeek/core/Failure$Code;)V");
    if (ctor) {
        auto error = static_cast<jthrowable>(env->NewObject(failure, ctor, value));
        if (error) { env->Throw(error); env->DeleteLocalRef(error); }
    }
    env->DeleteLocalRef(failure); env->DeleteLocalRef(value); env->DeleteLocalRef(enumClass);
}
} // namespace

extern "C" JNIEXPORT jint JNICALL Java_io_lumipeek_core_WindowsFiles_abi(JNIEnv*, jclass) { return 1; }
extern "C" JNIEXPORT jlongArray JNICALL Java_io_lumipeek_core_WindowsFiles_identify0(
        JNIEnv* env, jclass, jstring path, jlong timeout) {
    try {
        Budget budget(env, timeout); budget.check();
        Root root = openRoot(string(env, path), false, budget);
        auto id = key(root.get());
        jlongArray result = env->NewLongArray(3);
        if (result) env->SetLongArrayRegion(result, 0, 3, id.data());
        return result;
    } catch (const Denied& ex) { report(env, ex.code); }
    catch (...) { report(env, "WORK_FAILED"); }
    return nullptr;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_io_lumipeek_core_WindowsFiles_discover0(
        JNIEnv* env, jclass, jstring path, jlongArray id, jboolean recursive, jint limit, jlong timeout) {
    try {
        if (limit < 1 || limit > 100000) deny();
        Budget budget(env, timeout); budget.check();
        Root root = openRoot(string(env, path), true, budget);
        expected(env, id, root.get());
        fixturePause(4); budget.check();
        std::vector<std::wstring> found;
        int visited = 0; size_t pathChars = 0;
        walk(root.get(), L"", recursive != 0, limit, visited, 0, pathChars, found, budget);
        budget.check();
        jclass cls = env->FindClass("java/lang/String");
        if (!cls) return nullptr;
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(found.size()), cls, nullptr);
        env->DeleteLocalRef(cls);
        if (!result) return nullptr;
        for (size_t i = 0; i < found.size(); ++i) {
            budget.check();
            jstring name = env->NewString(reinterpret_cast<const jchar*>(found[i].data()), static_cast<jsize>(found[i].size()));
            if (!name) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(i), name); env->DeleteLocalRef(name);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    } catch (const Denied& ex) { report(env, ex.code); }
    catch (...) { report(env, "WORK_FAILED"); }
    return nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_lumipeek_core_WindowsFiles_read0(
        JNIEnv* env, jclass, jstring path, jlongArray id, jstring relative, jlong timeout) {
    try {
        Budget budget(env, timeout); budget.check();
        Root root = openRoot(string(env, path), false, budget);
        expected(env, id, root.get()); fixturePause(1); budget.check();
        auto parts = split(string(env, relative));
        if (!extension(parts.back())) deny("FORMAT_DENIED");
        for (size_t i = 0; i + 1 < parts.size(); ++i)
            root.pins.push_back(child(root.get(), parts[i], true, false, budget));
        fixturePause(2); budget.check();
        Handle file = child(root.get(), parts.back(), false, true, budget);
        auto before = validate(file.h, false);
        auto fileId = key(file.h);
        fixturePause(3); budget.check();
        std::array<unsigned char, 8> header{};
        DWORD position = 0;
        if (!ReadFile(file.h, header.data(), std::min<DWORD>(8, before.nFileSizeLow), &position, nullptr)) deny();
        bool png = sameText(parts.back().substr(parts.back().find_last_of(L'.')), L".png");
        static constexpr unsigned char signature[] = {137, 80, 78, 71, 13, 10, 26, 10};
        if (png ? (position < 8 || std::memcmp(header.data(), signature, 8) != 0)
                : (position < 3 || header[0] != 255 || header[1] != 216 || header[2] != 255)) deny("FORMAT_DENIED");
        std::vector<jbyte> bytes(before.nFileSizeLow);
        struct Wipe {
            std::vector<jbyte>& data;
            ~Wipe() { SecureZeroMemory(data.data(), data.size()); }
        } wipe{bytes};
        std::memcpy(bytes.data(), header.data(), position);
        while (position < before.nFileSizeLow) {
            budget.check(); DWORD received = 0;
            if (!ReadFile(file.h, bytes.data() + position, std::min<DWORD>(65536, before.nFileSizeLow - position), &received, nullptr)
                    || !received) deny();
            position += received;
        }
        auto after = validate(file.h, false);
        if (key(file.h) != fileId || after.nFileSizeLow != before.nFileSizeLow
                || CompareFileTime(&after.ftLastWriteTime, &before.ftLastWriteTime) != 0) deny();
        budget.check();
        jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), bytes.data());
        return result;
    } catch (const Denied& ex) { report(env, ex.code); }
    catch (...) { report(env, "WORK_FAILED"); }
    return nullptr;
}
