# LumiPeek

[Little LUMI Model](https://store.steampowered.com/app/5075020/) 사진 감상 확장을 개발하는 저장소입니다. 현재는 Windows 파일 접근·이미지 전처리·워커 격리 기반을 구현했으며, 설치 가능한 완성 모드가 아닙니다. 기존 꼬미 코드는 변경하지 않습니다.

## 구현된 기능

- Windows x64 고정 로컬 NTFS의 읽기 전용 폴더 등록·탐색·이미지 읽기. 열린 부모 핸들에 상대적으로 접근하고 볼륨·파일 ID를 검증합니다. 원본에 쓰기·수정·삭제 권한을 요청하지 않습니다.
- 링크·정션·재분석 지점·다중 하드링크·오프라인/회수 대상·UNC·네트워크·SUBST 경로 차단. 루트 교체·경쟁·권한 거부·쓰기 경합은 안전하게 실패합니다.
- PNG/JPEG 확장자·헤더·파일 25MiB·30MP 제한. 이미지 디코딩과 최대1024픽셀 메타데이터 제거 PNG 생성은 별도 JVM에서 수행합니다. 호스트는 크기가 검증된 RGB 픽셀을 받으며 이미지를 다시 디코딩하지 않습니다.
- Windows Job Object 워커: 정지 상태 생성→Job 배정→실행, 동시1개, JVM heap128MiB, 프로세스 commit 할당 한도256MiB, Job commit 한도384MiB. 사진 경로는 상속된 익명 메모리로 전달하고 명령행·임시 파일·로그에 넣지 않습니다. 결과 파이프는8MiB로 제한합니다.
- 취소·시간 초과 시 자신이 만든 워커만 종료하고 늦은 결과를 폐기합니다. 종료 확인 실패 시 `CLEANUP_FAILED`로 새 워커를 차단합니다. 자체 핸들·스레드 정리와 결과 버퍼 해제를 검증했습니다.
- 세션 중복 억제·단일 작업 큐·엄격한 감상 JSON 검사. 원본·경로·AI 원문을 공개 로그나 분석 캐시에 저장하지 않습니다.
- 공식 SDK **1.5.0** JAR로 작은 읽기 전용 플러그인을 컴파일하고 `PluginTestKit` 모의 컨텍스트의 시작·종료·재시작·quiet/bubble 조회를 검증했습니다. 실제 앱·Steam 엔진·GUI·Ollama는 실행하지 않았습니다.

## 검증과 제한

Windows NT10.0.26200 x64에서 합성 이미지와 프로젝트 임시 폴더로 검증했습니다. JDK21·25, Windows x64 Release 빌드, Java lint/경고 오류 처리, JNI 검사, MSVC 정적 분석 및 DLL 의존성·테스트 함수 제외 감사를 수행합니다. 테스트용 무한 대기·네이티브 대기·OOM·메모리 할당 실패·과도한 출력·비정상 종료를 포함합니다.

Job Object는 **자원 제한이며 파일·네트워크 권한 보안 샌드박스가 아닙니다**. commit 한도는 물리 RAM/RSS 한도와 다르고 OS 집계 비용도 존재합니다. 커널·필터 드라이버가 종료를 막는 경우의 실시간 종료 보장은 없으며 실패 시 추가 작업을 차단합니다. 실제 OneDrive/SMB, 다른 Windows 환경, 장기 부하, 실앱·다른 모드 호환성은 NOT VERIFIED입니다.

공식 main의 [SDK 메타데이터](https://github.com/studio-lumi-h/little-lumi-sdk/blob/main/sdk.json)는1.5.1이지만, 확인한 [공개 Releases](https://github.com/studio-lumi-h/little-lumi-sdk/releases)는1.5.0까지였습니다. **SDK1.5.1 바이너리·보호된 실행 사본의 native 로딩·실앱 코드 동의 검증은 BLOCKED**입니다. 1.5.0 모의 검사 결과를1.5.1 검증 완료로 간주하지 않습니다. Ollama 연결·생각 중 표시·미리보기 창은 아직 제품 기능으로 연결되지 않았습니다.

## 개발 검사

Java 코어에는 JDK17 이상과 PowerShell, Windows 검사에는 x64 MSVC Build Tools/Windows SDK가 필요합니다. SDK 검사에는 JDK25와 사용자가 지정한 공식 배포본이 필요합니다. 스크립트는 SDK·JDK·모델을 자동 다운로드하거나 실제 앱을 실행하지 않습니다.

```powershell
./scripts/test.ps1 -JdkHome '설치된 JDK 경로'
./scripts/test.ps1 -JdkHome '설치된 JDK 경로' -WindowsBoundary
./scripts/build-native.ps1 -JdkHome '설치된 JDK 경로' -Analyze
./scripts/test-sdk.ps1 -SdkHome '공식 SDK 1.5.0 경로' -JdkHome 'JDK25 경로' -ExpectedSdk 1.5.0
./scripts/audit.ps1
```

검사는 합성 데이터만 사용하며 산출물·SDK·개인 파일·내부 기록은 Git에서 제외합니다. 실제 게임이나 다른 프로그램의 프로세스·메모리·화면·입력에 접근하지 않습니다. 화면 캡처·전역 후킹·입력 자동화·DLL 주입을 구현하지 않습니다.
