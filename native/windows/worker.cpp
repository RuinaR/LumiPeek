// Supervises only the process created here. No PID lookup or external process access.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace {
constexpr DWORD MAX_REQUEST = 256 * 1024, MAX_OUTPUT = 8 * 1024 * 1024;
constexpr SIZE_T COMMIT_LIMIT = 384ULL * 1024 * 1024;
constexpr SIZE_T PROCESS_LIMIT = 256ULL * 1024 * 1024;
std::atomic<int> handles{0};
std::atomic<bool> poisoned{false};
std::atomic<bool> active{false};
#ifdef LUMIPEEK_TEST
std::atomic<jlong> lastOutputBytes{0};
#endif
struct Fault { const char* code; };
[[noreturn]] void fail(const char* code = "WORK_FAILED") { throw Fault{code}; }
struct Invocation {
    bool claimed = false;
    void claim() { if (active.exchange(true)) fail("BUSY"); claimed = true; }
    ~Invocation() { if (claimed) active = false; }
};
struct Handle {
    HANDLE value = nullptr;
    Handle() = default;
    explicit Handle(HANDLE h) : value(h) { if (valid()) ++handles; }
    bool valid() const { return value && value != INVALID_HANDLE_VALUE; }
    void reset(HANDLE h = nullptr) {
        if (valid()) { CloseHandle(value); --handles; }
        value = h; if (valid()) ++handles;
    }
    ~Handle() { reset(); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
};
struct Session {
    Handle job, process, thread;
    bool reaped = false;
    bool stop() {
        if (!process.valid() || reaped) return true;
        if (WaitForSingleObject(process.value, 0) != WAIT_OBJECT_0) {
            // Both handles come exclusively from this invocation's CreateProcess.
            if (job.valid()) TerminateJobObject(job.value, 1);
            TerminateProcess(process.value, 1); // also covers failure before assignment
        }
        reaped = WaitForSingleObject(process.value, 2000) == WAIT_OBJECT_0;
        if (!reaped) poisoned = true; // never admit another worker after uncertain cleanup
        return reaped;
    }
    ~Session() { stop(); }
};
struct Budget {
    JNIEnv* env;
    jobject owner = nullptr;
    jmethodID interrupted = nullptr;
    ULONGLONG start = GetTickCount64(), ms;
    Budget(JNIEnv* e, jlong timeout) : env(e), ms(static_cast<ULONGLONG>(timeout)) {
        if (timeout < 1 || timeout > 60000) fail();
        jclass cls = env->FindClass("java/lang/Thread"); if (!cls) fail();
        jmethodID current = env->GetStaticMethodID(cls, "currentThread", "()Ljava/lang/Thread;");
        if (!current) { env->DeleteLocalRef(cls); fail(); }
        owner = env->CallStaticObjectMethod(cls, current);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(cls); fail(); }
        interrupted = env->GetMethodID(cls, "isInterrupted", "()Z");
        env->DeleteLocalRef(cls); if (!owner || !interrupted) fail();
    }
    ~Budget() { if (owner) env->DeleteLocalRef(owner); }
    void check() {
        jboolean value = env->CallBooleanMethod(owner, interrupted);
        if (env->ExceptionCheck()) fail();
        if (value) fail("CANCELLED");
        if (GetTickCount64() - start >= ms) fail("TIMEOUT");
    }
};
std::wstring text(JNIEnv* env, jstring s) {
    if (!s) fail();
    jsize n = env->GetStringLength(s); if (n < 1 || n > 16000) fail();
    const jchar* chars = env->GetStringChars(s, nullptr); if (!chars) fail();
    std::wstring result;
    try { result.assign(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(n)); }
    catch (...) { env->ReleaseStringChars(s, chars); throw; }
    env->ReleaseStringChars(s, chars);
    if (result.find(L'\0') != std::wstring::npos) fail();
    return result;
}
std::wstring quote(const std::wstring& s) {
    // Windows command-line quoting, without a command interpreter.
    std::wstring result = L"\""; size_t slashes = 0;
    for (wchar_t c : s) {
        if (c == L'\\') { ++slashes; continue; }
        result.append(c == L'\"' ? slashes * 2 + 1 : slashes, L'\\');
        result += c; slashes = 0;
    }
    result.append(slashes * 2, L'\\'); return result + L'\"';
}
void report(JNIEnv* env, const char* code) {
    if (env->ExceptionCheck()) return;
    jclass ec = env->FindClass("io/lumipeek/core/Failure$Code"); if (!ec) return;
    jfieldID field = env->GetStaticFieldID(ec, code, "Lio/lumipeek/core/Failure$Code;");
    if (!field) { env->DeleteLocalRef(ec); return; }
    jobject value = env->GetStaticObjectField(ec, field);
    jclass fc = env->FindClass("io/lumipeek/core/Failure");
    if (!fc) { env->DeleteLocalRef(value); env->DeleteLocalRef(ec); return; }
    jmethodID ctor = env->GetMethodID(fc, "<init>", "(Lio/lumipeek/core/Failure$Code;)V");
    if (ctor) {
        auto error = static_cast<jthrowable>(env->NewObject(fc, ctor, value));
        if (error) { env->Throw(error); env->DeleteLocalRef(error); }
    }
    env->DeleteLocalRef(fc); env->DeleteLocalRef(value); env->DeleteLocalRef(ec);
}
struct Attributes {
    std::vector<unsigned char> storage;
    LPPROC_THREAD_ATTRIBUTE_LIST list = nullptr;
    Attributes(HANDLE* inherited, SIZE_T count) {
        SIZE_T bytes = 0; InitializeProcThreadAttributeList(nullptr, 1, 0, &bytes);
        if (!bytes || bytes > 1024 * 1024) fail();
        storage.resize(bytes);
        auto* allocated = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
        if (!allocated) throw Fault{"WORK_FAILED"};
        if (!InitializeProcThreadAttributeList(allocated, 1, 0, &bytes)) fail();
        if (!UpdateProcThreadAttribute(allocated, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited,
                count * sizeof(HANDLE), nullptr, nullptr)) {
            DeleteProcThreadAttributeList(allocated); fail();
        }
        list = allocated;
    }
    ~Attributes() { if (list) DeleteProcThreadAttributeList(list); }
};
struct WipeVector {
    std::vector<jbyte>& value;
    ~WipeVector() { if (!value.empty()) SecureZeroMemory(value.data(), value.size()); }
};
}

extern "C" JNIEXPORT jint JNICALL Java_io_lumipeek_core_WorkerBridge_liveHandles0(JNIEnv*, jclass) { return handles.load(); }
extern "C" JNIEXPORT jlongArray JNICALL Java_io_lumipeek_core_WorkerBridge_available0(JNIEnv* env, jclass) {
    MEMORYSTATUSEX status{}; status.dwLength = sizeof(status);
    if (!GlobalMemoryStatusEx(&status)) { report(env, "WORK_FAILED"); return nullptr; }
    jlong values[] = {static_cast<jlong>(status.ullAvailPhys), static_cast<jlong>(status.ullAvailPageFile)};
    jlongArray result = env->NewLongArray(2); if (result) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_lumipeek_core_WorkerBridge_request0(JNIEnv* env, jclass, jlong raw) {
    // The sole handle argument is an inherited read-only anonymous mapping, not a path.
    HANDLE h = reinterpret_cast<HANDLE>(static_cast<INT_PTR>(raw));
    const void* view = MapViewOfFile(h, FILE_MAP_READ, 0, 0, MAX_REQUEST + sizeof(DWORD));
    if (!view) { CloseHandle(h); report(env, "WORK_FAILED"); return nullptr; }
    DWORD size = 0; std::memcpy(&size, view, sizeof(size));
    jbyteArray result = nullptr;
    if (size <= MAX_REQUEST) {
        result = env->NewByteArray(static_cast<jsize>(size));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
            reinterpret_cast<const jbyte*>(view) + sizeof(DWORD));
    }
    UnmapViewOfFile(view); CloseHandle(h);
    if (!result && !env->ExceptionCheck()) report(env, "WORK_FAILED");
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_lumipeek_core_WorkerBridge_run0(
        JNIEnv* env, jclass, jstring executable, jstring classpath, jstring bridgePath,
        jbyteArray input, jlong timeout, jint fixture) {
    Invocation invocation;
    Session session;
    try {
        invocation.claim();
#ifdef LUMIPEEK_TEST
        lastOutputBytes = 0;
#endif
        if (poisoned || !input) fail("CLEANUP_FAILED");
#ifndef LUMIPEEK_TEST
        if (fixture) fail();
#endif
        Budget budget(env, timeout); budget.check();
        std::wstring java = text(env, executable), cp = text(env, classpath), dll = text(env, bridgePath);
        jsize size = env->GetArrayLength(input);
        if (size < 1 || static_cast<DWORD>(size) > MAX_REQUEST) fail();
        Handle mapping(CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, MAX_REQUEST + sizeof(DWORD), nullptr));
        if (!mapping.valid()) fail();
        void* view = MapViewOfFile(mapping.value, FILE_MAP_WRITE, 0, 0, MAX_REQUEST + sizeof(DWORD));
        if (!view) fail();
        DWORD count = static_cast<DWORD>(size); std::memcpy(view, &count, sizeof(count));
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(view) + sizeof(count));
        UnmapViewOfFile(view); if (env->ExceptionCheck()) fail();
        HANDLE readMapRaw = nullptr;
        if (!DuplicateHandle(GetCurrentProcess(), mapping.value, GetCurrentProcess(), &readMapRaw, FILE_MAP_READ, TRUE, 0)) fail();
        Handle readMap(readMapRaw);
        SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
        HANDLE pipeRead = nullptr, pipeWrite = nullptr;
        if (!CreatePipe(&pipeRead, &pipeWrite, &sa, 65536)) fail();
        Handle output(pipeRead), outputChild(pipeWrite);
        if (!SetHandleInformation(output.value, HANDLE_FLAG_INHERIT, 0)) fail();
        Handle nul(CreateFileW(L"NUL", GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
            &sa, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
        if (!nul.valid()) fail();
        session.job.reset(CreateJobObjectW(nullptr, nullptr)); if (!session.job.valid()) fail();
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            | JOB_OBJECT_LIMIT_ACTIVE_PROCESS | JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_JOB_MEMORY;
        limits.BasicLimitInformation.ActiveProcessLimit = 1;
        // Keep aggregate headroom above the per-process allocation cap for OS accounting.
        limits.ProcessMemoryLimit = PROCESS_LIMIT; limits.JobMemoryLimit = COMMIT_LIMIT;
        if (!SetInformationJobObject(session.job.value, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) fail();
        HANDLE inherited[] = {readMap.value, outputChild.value, nul.value};
        Attributes attributes(inherited, 3);
        STARTUPINFOEXW startup{}; startup.StartupInfo.cb = sizeof(startup);
        startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        startup.StartupInfo.hStdInput = nul.value; startup.StartupInfo.hStdError = nul.value;
        startup.StartupInfo.hStdOutput = outputChild.value; startup.lpAttributeList = attributes.list;
        std::wstring command = quote(java) + L" -Xms16m -Xmx128m -Xss256k -XX:+UseSerialGC"
            L" -XX:ActiveProcessorCount=2 -XX:MaxMetaspaceSize=64m -XX:CompressedClassSpaceSize=32m"
            L" -XX:ReservedCodeCacheSize=32m -XX:+ExitOnOutOfMemoryError -XX:-CreateCoredumpOnCrash"
            L" -XX:+DisableAttachMechanism -XX:ErrorFile=NUL -Djava.awt.headless=true"
            L" --enable-native-access=ALL-UNNAMED -cp " + quote(cp)
            + (fixture ? L" io.lumipeek.core.WorkerFaults " : L" io.lumipeek.core.PhotoWorkerMain ")
            + std::to_wstring(reinterpret_cast<UINT_PTR>(readMap.value)) + L" " + quote(dll);
        // Environment is intentionally empty: no JAVA_TOOL_OPTIONS, agents or injected JVM flags.
        wchar_t environment[] = {0, 0};
        if (command.size() >= 32760) fail();
        PROCESS_INFORMATION process{};
        if (!CreateProcessW(java.c_str(), command.data(), nullptr, nullptr, TRUE,
                CREATE_SUSPENDED | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT,
                environment, nullptr, &startup.StartupInfo, &process)) fail();
        session.process.reset(process.hProcess); session.thread.reset(process.hThread);
        if (!AssignProcessToJobObject(session.job.value, session.process.value)) fail();
        // No child instruction runs before containment succeeds.
        if (ResumeThread(session.thread.value) == static_cast<DWORD>(-1)) fail();
        session.thread.reset(); readMap.reset(); outputChild.reset(); nul.reset();
        std::vector<jbyte> bytes;
        bytes.reserve(MAX_OUTPUT); WipeVector wipeBytes{bytes};
        for (;;) {
            budget.check(); DWORD available = 0;
            BOOL ok = PeekNamedPipe(output.value, nullptr, 0, nullptr, &available, nullptr);
            if (!ok && GetLastError() != ERROR_BROKEN_PIPE) fail();
            if (ok && available) {
                if (available > MAX_OUTPUT - bytes.size()) fail();
                size_t old = bytes.size(); DWORD part = std::min<DWORD>(available, 65536);
                bytes.resize(old + part); DWORD received = 0;
                if (!ReadFile(output.value, bytes.data() + old, part, &received, nullptr) || !received) fail();
                bytes.resize(old + received);
#ifdef LUMIPEEK_TEST
                lastOutputBytes = static_cast<jlong>(bytes.size());
#endif
                continue;
            }
            DWORD state = WaitForSingleObject(session.process.value, 10);
            if (state == WAIT_OBJECT_0) {
                // Drain data already buffered when the worker exited.
                if (PeekNamedPipe(output.value, nullptr, 0, nullptr, &available, nullptr) && available) continue;
                session.reaped = true; break;
            }
            if (state != WAIT_TIMEOUT) fail();
        }
        budget.check();
        DWORD exit = 0;
        if (!GetExitCodeProcess(session.process.value, &exit)) fail();
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION observed{};
        if (!QueryInformationJobObject(session.job.value, JobObjectExtendedLimitInformation, &observed, sizeof(observed), nullptr)) fail();
        // Trusted envelope: exit status, configured OS cap, peak job commit, response bytes.
        const jlong values[] = {static_cast<jlong>(exit), static_cast<jlong>(observed.JobMemoryLimit),
            static_cast<jlong>(observed.PeakJobMemoryUsed), static_cast<jlong>(observed.ProcessMemoryLimit)};
        std::vector<jbyte> result(sizeof(values) + bytes.size());
        WipeVector wipeResult{result};
        std::memcpy(result.data(), values, sizeof(values));
        if (!bytes.empty()) std::memcpy(result.data() + sizeof(values), bytes.data(), bytes.size());
        jbyteArray answer = env->NewByteArray(static_cast<jsize>(result.size()));
        if (answer) env->SetByteArrayRegion(answer, 0, static_cast<jsize>(result.size()), result.data());
        return answer;
    } catch (const Fault& fault) {
        report(env, session.stop() ? fault.code : "CLEANUP_FAILED");
    } catch (...) { report(env, session.stop() ? "WORK_FAILED" : "CLEANUP_FAILED"); }
    return nullptr;
}

#ifdef LUMIPEEK_TEST
extern "C" JNIEXPORT jlong JNICALL Java_io_lumipeek_core_WorkerFaultTests_lastOutput0(JNIEnv*, jclass) {
    return lastOutputBytes.load();
}
extern "C" JNIEXPORT void JNICALL Java_io_lumipeek_core_WorkerFaults_block0(JNIEnv*, jclass) {
    Sleep(INFINITE); // deliberately uncooperative native wait in this child only
}
extern "C" JNIEXPORT void JNICALL Java_io_lumipeek_core_WorkerFaults_abort0(JNIEnv*, jclass) {
    ExitProcess(9); // abrupt own-worker exit, without Java cleanup or error dialogs
}
extern "C" JNIEXPORT jint JNICALL Java_io_lumipeek_core_WorkerFaults_commit0(JNIEnv*, jclass) {
    std::vector<void*> pages;
    while (void* page = VirtualAlloc(nullptr, 8 * 1024 * 1024, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE)) pages.push_back(page);
    int count = static_cast<int>(pages.size());
    for (void* page : pages) VirtualFree(page, 0, MEM_RELEASE);
    return count;
}
#endif
