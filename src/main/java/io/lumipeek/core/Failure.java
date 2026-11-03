package io.lumipeek.core;

/** Public diagnostics contain codes only, never paths, model output or causes. */
public final class Failure extends Exception {
    private static final long serialVersionUID = 1L;
    public enum Code { PATH_DENIED, LINK_DENIED, FILE_LIMIT, FORMAT_DENIED,
        PIXEL_LIMIT, DECODE_FAILED, TIMEOUT, CANCELLED, RESPONSE_INVALID, WORK_FAILED, BUSY, CLEANUP_FAILED }
    private final Code code;
    public Failure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
