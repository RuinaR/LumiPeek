package io.lumipeek.sdk;

import java.util.List;

/** Reviewed official library tags. Sizes are estimates, never an allocation or download guarantee. */
public final class ModelCatalog {
    public record Entry(String tag, String download, String source, String license) {
        @Override public String toString() { return tag; }
        public String consentText() {
            return tag + "\n예상 다운로드: " + download + " (태그 변경 시 달라질 수 있음)\n"
                + "출처: " + source + "\n라이선스: " + license
                + "\n저장 공간은 다운로드 크기 이상 필요하며 임시 파일·메모리·VRAM은 별도입니다."
                + "\n로컬 Ollama가 공식 레지스트리에서 내려받습니다. 사진은 전송하지 않습니다."
                + "\n취소 후 서버 다운로드가 즉시 멈추는지는 보장하지 않습니다."
                + "\n다운로드하시겠습니까?";
        }
    }
    public static final List<Entry> ENTRIES = List.of(entry("2b", "약 1.9 GB"), entry("4b", "약 3.3 GB"), entry("8b", "약 6.2 GB"));
    private ModelCatalog() {}
    private static Entry entry(String size, String download) {
        String tag = "qwen3-vl:" + size + "-instruct";
        return new Entry(tag, download, "https://ollama.com/library/" + tag, "Apache-2.0");
    }
    public static Entry require(String tag) {
        return ENTRIES.stream().filter(e -> e.tag().equals(tag)).findFirst().orElseThrow(() -> new IllegalArgumentException("MODEL_NOT_ALLOWED"));
    }
}
