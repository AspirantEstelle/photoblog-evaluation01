# PhotoBlog 공통평가 01

2022105748 조연흠 · 2026년 2학기 모바일/웹서비스 프로젝트

- 제출 저장소: https://github.com/AspirantEstelle/photoblog-evaluation01
- 배포 서비스: https://gyusik.pythonanywhere.com/
- 공개 API: https://gyusik.pythonanywhere.com/api_root/Post/
- 수행 결과 보고서: 루트의 `모바일웹서비스 프로젝트_공통평가 01_수행 결과 보고서.docx`

보고서 ID 3에는 브라우저 주소 표시줄이 포함된 API 입력 화면과 게시 완료 화면을 수록했다. 실제 생성 시 저장한 HTTP 201 응답, 게시글 ID 5의 상세 화면과 함께 검증 결과를 제시한다. 입력 폼은 같은 제목·본문·사진을 준비해 재확인한 화면이다.

## 구성

```text
ROOT/
├── PhotoBlogServer/     Django + Django REST framework
├── PhotoViewer/         Java Android 앱과 기기 통합 테스트
├── evidence/            실제 실행 캡처, 응답 JSON, 요청 원본, 테스트 기록
└── 모바일웹서비스 프로젝트_공통평가 01_수행 결과 보고서.docx
```

교재 ch05의 `kiokahn/web_blog`를 기반으로 이미지 게시글 API와 안드로이드 클라이언트를 구현했다. 서버의 `/api_root/Post/` 경로와 JSON 배열 응답을 유지했다. 업로드는 평가 항목의 **제목·본문·사진 선택 UI 제공(상)** 방식이다. Android의 폐기된 AsyncTask 대신 ExecutorService와 메인 스레드 콜백을 사용했다. RecyclerView로 목록을 표시한다.

## 서버 실행

실습 사진은 제공받은 `icon_5.png`와 `뭐여.png`를 변경 없이 사용했다. 서버의 `samples/cat_annoyed.png`, `samples/cat_what.png`와 Android 테스트 자산에 포함했다. 웹과 앱 목록은 그림 전체가 보이도록 표시한다. 기존 실습 DB의 `photo_demo` 예제 사진은 `python manage.py refresh_demo_images`로 교체할 수 있다. 2026년 10월 13일 고양이 사진으로 업로드·동기화·캐시·소켓 테스트와 보고서 캡처를 다시 실행했다.

Python 3.12 이상이 필요하다. 검증 환경은 로컬 Python 3.14, 클라우드 Python 3.13이다.

```sh
cd PhotoBlogServer
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python manage.py migrate
python manage.py seed_demo
python manage.py runserver 127.0.0.1:8000
```

`seed_demo`는 예제 작성자와 사진 한 장을 만들고 임의의 로그인 정보 및 API 토큰을 `.demo-credentials.json`에 저장한다. 이 파일, DB, 업로드 원본은 Git에서 제외한다. 기존 작성자의 비밀번호는 변경하지 않는다. 본인 관리자 계정이 필요하면 `python manage.py createsuperuser`로 생성한다.

웹: http://127.0.0.1:8000/ · API: http://127.0.0.1:8000/api_root/Post/ · 관리: http://127.0.0.1:8000/admin/

## Android 실행

1. Android Studio에서 `PhotoViewer` 폴더를 연다. JDK 17 이상, Android SDK 35가 필요하다.
2. Android 11(API 30) 이상 기기 또는 에뮬레이터를 선택하고 Run을 누른다. 검증 기기는 Medium Phone API 36이다.
3. 설정에서 **로컬** 또는 **클라우드**를 고른 뒤 저장하고 **동기화**를 누른다.
4. 글 작성 시 해당 서버의 API 토큰을 설정에 입력한다. 로컬 토큰은 위 비공개 JSON 파일에 있다.
5. **글 올리기 → 제목·본문 → 사진 선택 → 업로드** 순서로 게시한다.

에뮬레이터에서 `10.0.2.2`는 PC를 뜻한다. 실물 기기는 클라우드 주소를 사용한다. 클라우드 HTTPS 연결과 에뮬레이터 loopback 주소만 허용하고 인터넷 전체의 HTTP는 허용하지 않는다. 토큰은 앱 전용 저장소에 보관하며 백업을 끈다. 로컬 토큰을 클라우드에 자동으로 복사하지 않는다.

## API

| 요청 | 의미 | 기대 응답 |
|---|---|---|
| GET `/api_root/Post/` | 전체 게시글 | 200, JSON 배열 |
| GET `/api_root/Post/{id}/` | 단일 게시글 | 200 / 404 |
| POST `/api_root/Post/` | multipart 이미지 게시 | 201 |
| PATCH `/api_root/Post/{id}/` | 소유자/관리자 수정 | 200 |
| DELETE `/api_root/Post/{id}/` | 소유자/관리자 삭제 | 204 |
| POST `/api-token-auth/` | username, password로 토큰 획득 | 200 / 400 |

읽기는 공개하고 작성은 `Authorization: Token <token>` 또는 로그인 세션과 CSRF 검증이 필요하다. author는 로그인한 사용자로 서버가 지정하므로 다른 작성자를 지정할 수 없다. 예전 과제 예문의 JWT 문자열을 이 프로젝트에서 재사용하지 않는다. JPEG/PNG/GIF/WebP, 최대 5MB만 웹과 API에서 받는다.

```sh
curl -X POST http://127.0.0.1:8000/api_root/Post/ \
  -H "Authorization: Token <본인의 토큰>" \
  -H "Accept: application/json" \
  -F "title=새 사진" -F "text=이미지 업로드 실습" \
  -F "image=@samples/cat_annoyed.png;type=image/png"
```

Windows PowerShell에서는 `curl.exe`를 사용하고 줄바꿈 없이 한 줄로 입력할 수 있다.

## 검증

```sh
cd PhotoBlogServer
python -m coverage run manage.py test blog -v 2
python -m coverage report
python -m coverage html
python tools/verify_api.py
```

서버 17개 테스트가 통과했다. 측정 대상 `blog`의 실행문 109개와 분기 14개는 100% 실행됐다. 이는 `.coveragerc`에 지정한 범위로, 테스트 자체·마이그레이션·초기 데이터 명령과 Android 코드는 이 비율에 포함하지 않는다. 정상 응답, 한글 multipart 업로드, 잘못된 이미지/용량 제한, 작성자 위조, 무인증 작성, 소유권, 수정·삭제, 웹 폼을 검사한다.

Android 기기 테스트 소스는 `app/src/androidTest/.../EvidenceRunner.java`에 있다. 실제 앱을 실행해 동기화, 검색, 상세 표시, 초안 복원, multipart 업로드와 업로드 후 새 목록을 검증한다. 이미지 선택 결과 URI를 테스트 코드가 주입하고, 이후 서버 요청은 실제 운영 코드가 수행한다. 스크린샷은 에뮬레이터 UiAutomation의 실제 화면이다. 로컬·클라우드·캐시 복원·소켓 연동 테스트 기록은 `evidence/`에 있다.

요청 수신 실습은 `python tools/capture_request.py`를 실행하고 앱 서버를 `http://10.0.2.2:8001`로 설정한 뒤 동기화한다. 한 번의 익명 GET 요청을 변경하지 않은 `.bin` 파일에 보관하고 Django 8000으로 전달한다. `.txt`는 편집기 확인을 위해 줄바꿈만 정규화한 사본이다. 8001 소켓은 단발성 검증용이므로 끝나면 앱 설정을 로컬 8000으로 복원한다.

## 추가 편의 기능 5개

1. 로컬/클라우드 주소 프리셋과 연결 설정 저장.
2. 내려받은 제목과 본문의 즉시 검색.
3. 항목을 누르면 큰 사진과 전체 본문을 표시.
4. 서버별 목록과 사진 캐시로 앱 재실행 시 이전 내용을 표시.
5. 작성 창을 닫았다 다시 열어도 제목·본문 초안 복원.

## PythonAnywhere 배포

현재 코드 경로: `/home/Gyusik/photoblog-evaluation01/PhotoBlogServer`.
Python 3.13 가상환경 `/home/Gyusik/my-first-blog/.venv`의 호환 라이브러리를 사용한다.
`private-settings.json`에는 임의의 secret_key와 `"debug": "0"`을 두고 Git에 올리지 않는다. WSGI는 새 프로젝트 경로를 등록하고 `mysite.settings`를 불러온다. 기존 프로젝트 폴더와 WSGI 백업은 보존했다.

| 정적 URL | 디렉터리 |
|---|---|
| `/static/` | `/home/Gyusik/photoblog-evaluation01/PhotoBlogServer/staticfiles` |
| `/media/` | `/home/Gyusik/photoblog-evaluation01/PhotoBlogServer/media` |

수정 후 서버 폴더에서 `git pull`, `python manage.py migrate`, `python manage.py collectstatic --noinput`을 실행하고 Web 탭의 Reload로 적용한다. 무료 사이트의 유지 기간은 PythonAnywhere Web 탭에서 확인하고 갱신한다.

참고: [교재 예비 코드](https://github.com/kiokahn/web_blog), [DRF 테스트](https://www.django-rest-framework.org/api-guide/testing/), [Android 문서 선택기](https://developer.android.com/training/data-storage/shared/documents-files), [PythonAnywhere 배포](https://help.pythonanywhere.com/pages/DeployExistingDjangoProject/).

## 제출 전 재점검

2026년 10월 13일 재점검에서 누락된 이미지 경로 migration, 웹과 API 사이의 이미지 제한 불일치, Android 네트워크 설정 오류, 서버 전환 시 토큰 표시와 화면 재생성 처리를 수정했다. 서버 17개 테스트와 새 DB 초기화, Android 빌드 및 실제 기기 경계 상황 검사를 통과했다. Lint는 오류 0개이며 문자열 리소스·SDK 갱신·UI 스타일 등의 권고 경고 18개가 남는다. 현재 제출용 APK 실행을 막는 오류는 아니다.

기기 추가 검사는 서버별 토큰 선택과 삭제, 회전 후 초안 복원, 지연된 업로드 응답 도착 시 화면 재생성을 다룬다. 지연 응답 검사는 에뮬레이터 내부의 테스트 HTTP 서버를 사용하며, 실제 Django 업로드는 별도의 새 DB 서버에서도 검증했다. 검사 기록은 evidence/android-audit-test.txt, android-clean-install-test.txt, clean-install.txt, android-lint.txt에 있다.

PythonAnywhere 무료 사이트는 2026년 11월 13일까지 유지하도록 갱신했다. 그 이후에도 채점이나 시연이 필요하면 Web 탭에서 무료 유지 기간을 다시 갱신한다. 주소 표시줄이 포함된 사용자 캡처 2장을 원본 그대로 evidence 폴더와 보고서에 추가해 남은 증빙 보완을 완료했다.
